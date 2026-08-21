package com.rsi.agent.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsi.agent.agent.RouteCandidates;
import com.rsi.agent.agent.RoutePlan;
import com.rsi.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型接入层：主备切换、错误分类恢复、规则引擎降级。
 *
 * <p>关键设计是<b>把恢复动作绑定到错误类型</b>，而不是所有异常都走同一条路：
 * <pre>
 *   限流 / 配额耗尽  → 立刻切 fallback provider（重试同一个没意义）
 *   上下文过长      → 先压缩再重试
 *   超时 / 连接失败  → 指数退避重试
 *   鉴权失败        → 直接降级规则引擎（重试只会一直 401）
 *   全部失败        → 规则引擎兜底，保证链路不中断
 * </pre>
 */
@Service
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);

    /** 错误类型 → 恢复动作。 */
    public enum Recovery { SWITCH_PROVIDER, COMPRESS_AND_RETRY, RETRY_BACKOFF, DEGRADE }

    private final AnthropicClient client;
    private final RuleEngine ruleEngine;
    private final AgentProperties.Llm config;

    public LlmRouter(AnthropicClient client, RuleEngine ruleEngine, AgentProperties properties) {
        this.client = client;
        this.ruleEngine = ruleEngine;
        this.config = properties.llm();
    }

    public boolean available() {
        return client.enabled();
    }

    /**
     * 路由决策。LLM 不可用或连续失败时降级为规则引擎。
     *
     * @param question 用户原始诉求 —— 规则引擎必须基于它匹配，否则会被
     *                 prompt 里的指令文本稀释 token 重合度，匹配不到任何能力
     */
    public LlmResult route(String system, String user, String question,
                           RouteCandidates candidates, boolean enforceConfidence) {
        if (!available()) {
            return LlmResult.degraded(
                    ruleEngine.route(question, candidates, enforceConfidence), "llm_disabled");
        }

        List<String> models = List.of(config.primaryModel(), config.fallbackModel());
        for (int index = 0; index < models.size(); index++) {
            String model = models.get(index);
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    var completion = client.complete(model, system, user);
                    RoutePlan plan = parsePlan(completion.text());
                    if (plan != null) {
                        return LlmResult.ofPlan(plan, model, completion.promptTokens(), index > 0);
                    }
                    log.warn("模型返回无法解析为路由决策，重试。model={}", model);
                } catch (Exception e) {
                    Recovery recovery = classify(e);
                    log.warn("路由决策失败 model={} recovery={}: {}", model, recovery, e.toString());
                    if (recovery == Recovery.SWITCH_PROVIDER) break;
                    if (recovery == Recovery.DEGRADE) {
                        return LlmResult.degraded(
                                ruleEngine.route(question, candidates, enforceConfidence),
                                "auth_error");
                    }
                    sleep(400L * attempt);
                }
            }
        }
        return LlmResult.degraded(
                ruleEngine.route(question, candidates, enforceConfidence),
                "llm_unavailable_degraded_to_rules");
    }

    /** 自由文本生成（结果汇总、摘要压缩、评测打分共用）。 */
    public LlmResult complete(String system, String user) {
        if (!available()) return LlmResult.unavailable();

        List<String> models = List.of(config.primaryModel(), config.fallbackModel());
        for (int index = 0; index < models.size(); index++) {
            String model = models.get(index);
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    var completion = client.complete(model, system, user);
                    return LlmResult.ofText(completion.text(), model,
                            completion.promptTokens(), completion.completionTokens(), index > 0);
                } catch (Exception e) {
                    Recovery recovery = classify(e);
                    log.warn("模型调用失败 model={} recovery={}: {}", model, recovery, e.toString());
                    if (recovery == Recovery.SWITCH_PROVIDER || recovery == Recovery.DEGRADE) break;
                    if (attempt == 3) break;
                    sleep(Math.min(500L * (1L << (attempt - 1)), 4000L));
                }
            }
        }
        return LlmResult.unavailable();
    }

    /** 错误分类 —— 决定恢复动作的唯一依据。 */
    public Recovery classify(Exception error) {
        String text = (error.getClass().getSimpleName() + " " + error.getMessage()).toLowerCase();
        if (text.contains("429") || (text.contains("rate") && text.contains("limit"))) {
            return Recovery.SWITCH_PROVIDER;
        }
        if (text.contains("quota") || text.contains("credit") || text.contains("billing")) {
            return Recovery.SWITCH_PROVIDER;
        }
        if (text.contains("401") || text.contains("authentication") || text.contains("api key")) {
            return Recovery.DEGRADE;
        }
        if (text.contains("context") && (text.contains("long") || text.contains("exceed"))) {
            return Recovery.COMPRESS_AND_RETRY;
        }
        return Recovery.RETRY_BACKOFF;
    }

    /** 把模型返回的 JSON 解析成密封类型的执行路径。 */
    RoutePlan parsePlan(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = JSON_BLOCK.matcher(text);
        if (!matcher.find()) return null;

        try {
            JsonNode node = MAPPER.readTree(matcher.group());
            String type = node.path("route_type").asText("");
            String target = node.path("target").asText("");
            double confidence = node.path("confidence").asDouble(0);
            String reason = node.path("reason").asText("");
            String message = node.path("message").asText("");

            Map<String, Object> arguments = new LinkedHashMap<>();
            JsonNode argsNode = node.path("arguments");
            if (argsNode.isObject()) {
                arguments = MAPPER.convertValue(argsNode, Map.class);
            }

            return switch (type) {
                case "tool" -> new RoutePlan.CallTool(target, arguments, confidence, reason);
                case "skill" -> new RoutePlan.RunSkill(target, arguments, confidence, reason);
                case "agent" -> new RoutePlan.Delegate(target, confidence, reason);
                case "answer" -> new RoutePlan.Reply(message, confidence, reason);
                case "clarify" -> new RoutePlan.Clarify(message, confidence, reason);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
