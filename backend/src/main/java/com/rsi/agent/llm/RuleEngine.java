package com.rsi.agent.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rsi.agent.agent.Candidate;
import com.rsi.agent.agent.RouteCandidates;
import com.rsi.agent.agent.RoutePlan;
import com.rsi.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

/**
 * 规则引擎 —— LLM 不可用时的确定性路由。
 *
 * <p>它不是摆设：没有 API Key 时，平台的路由、Skill 编排、子 Agent 委派、
 * 审批中断、观测落库全链路仍然跑得通。这也是「LLM 全部失败时降级至规则引擎」
 * 这条容错策略的真实落地。
 *
 * <p>打分由四个因子构成，每一条都是实测调出来的：
 * <ol>
 *   <li><b>词面重合</b>：中文按字 + 二元组切分，分母做饱和处理
 *       （长问句的 token 数会被二元组撑大，直接除以总数会系统性低估匹配度）</li>
 *   <li><b>正负样例</b>：正样例整句命中给强加分，负样例命中直接压制</li>
 *   <li><b>实体感知</b>：问题里的 E1001 / INC-xxx 正好是该能力入参时加分</li>
 *   <li><b>参数可满足性</b>：必填参数抽不出来的能力降权，
 *       避免选中「更像但根本跑不通」的工具</li>
 * </ol>
 */
@Component
public class RuleEngine {

    /** 业务实体识别模式：字段名 → 正则。 */
    private static final Map<String, Pattern> ENTITY_PATTERNS = new LinkedHashMap<>();

    static {
        ENTITY_PATTERNS.put("employee_id", Pattern.compile("\\b(E\\d{4,})\\b", Pattern.CASE_INSENSITIVE));
        ENTITY_PATTERNS.put("ticket_id", Pattern.compile("\\b(INC-[0-9]+)\\b", Pattern.CASE_INSENSITIVE));
        ENTITY_PATTERNS.put("customer_id", Pattern.compile("\\b(C-\\d{3,})\\b", Pattern.CASE_INSENSITIVE));
        ENTITY_PATTERNS.put("request_id", Pattern.compile("\\b(RB-[\\d-]+)\\b", Pattern.CASE_INSENSITIVE));
    }

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "吗", "呢", "啊", "请", "帮", "我", "一下", "这个", "那个",
            "可以", "能否", "需要", "怎么", "如何", "是否", "一个",
            "and", "the", "a", "an", "is", "are", "to", "of", "for", "my", "me", "please", "help");

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9_]+");

    private final double confidenceThreshold;

    public RuleEngine(AgentProperties properties) {
        this.confidenceThreshold = properties.runtime().routeConfidenceThreshold();
    }

    /**
     * 确定性路由。
     *
     * @param enforceConfidence 仅对 Supervisor 为 true。主 Agent 面对全域能力，
     *                          低置信度执行风险高，应转澄清；子 Agent 的领域边界
     *                          已由上层确定，不该被阈值二次拦截。
     */
    public RoutePlan route(String question, RouteCandidates candidates, boolean enforceConfidence) {
        Set<String> tokens = tokenize(question);
        Map<String, String> entities = detectEntities(question);

        // 分层加权：体现「优先子 Agent / Skill、其次单工具」的收敛策略
        Map<Candidate.Kind, Double> weights = Map.of(
                Candidate.Kind.AGENT, 1.0, Candidate.Kind.SKILL, 1.05, Candidate.Kind.TOOL, 0.95);

        Candidate best = null;
        double bestScore = 0;
        double runnerUp = 0;

        for (Candidate candidate : candidates.all()) {
            double score = score(tokens, candidate) * weights.getOrDefault(candidate.kind(), 1.0);

            if (score > 0) {
                Set<String> accepted = new LinkedHashSet<>(candidate.argNames());
                accepted.addAll(candidate.requiredArgs());

                boolean entityMatch = !entities.isEmpty()
                        && accepted.stream().anyMatch(entities::containsKey);
                if (entityMatch) {
                    score += 0.2;
                } else if (!entities.isEmpty() && candidate.kind() == Candidate.Kind.TOOL
                        && !accepted.isEmpty()) {
                    score -= 0.05;
                }

                Map<String, Object> satisfiable = extractArguments(question,
                        candidate.requiredArgs(), candidate.argNames());
                boolean missingRequired = candidate.requiredArgs().stream()
                        .anyMatch(key -> !satisfiable.containsKey(key));
                if (!candidate.requiredArgs().isEmpty() && missingRequired) {
                    score *= 0.5;
                }
            }

            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                best = candidate;
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }

        if (best == null || bestScore < 0.2) {
            // 返回的文案本身就是在请用户补充信息，因此标记为 clarify 而非 answer ——
            // 让「路径标签」和「实际说的话」保持一致，观测与评测才不会互相打架
            return new RoutePlan.Clarify(
                    "我没有找到可以直接处理该请求的内部能力。你可以补充更多信息"
                            + "（例如工号、工单号、客户名），或换一种说法再试一次。",
                    0.3, "规则引擎未匹配到任何能力，转为向用户澄清");
        }

        // 置信度 = 绝对分 + 与次优的差距
        double confidence = Math.min(0.95,
                0.45 + bestScore * 0.4 + Math.max(0, bestScore - runnerUp) * 0.5);
        confidence = Math.round(confidence * 100) / 100.0;

        if (enforceConfidence && confidence < confidenceThreshold) {
            return new RoutePlan.Clarify(
                    "我不太确定你的意图。你是想让我处理「%s」相关的事情吗？请再补充一些信息"
                            .formatted(abbreviate(best.description(), 40))
                            + "（例如工号、工单号、客户名或具体业务动作）。",
                    confidence,
                    "最佳匹配 %s:%s 置信度 %.2f 低于阈值 %.2f，不执行以避免误路由"
                            .formatted(best.kind().lower(), best.name(), confidence, confidenceThreshold));
        }

        String reason = "规则引擎匹配：命中关键词得分 %.2f（次优 %.2f）".formatted(bestScore, runnerUp);
        return switch (best.kind()) {
            case AGENT -> new RoutePlan.Delegate(best.name(), confidence, reason);
            case SKILL -> new RoutePlan.RunSkill(best.name(),
                    extractArguments(question, best.requiredArgs(), best.argNames()), confidence, reason);
            case TOOL -> new RoutePlan.CallTool(best.name(),
                    extractArguments(question, best.requiredArgs(), best.argNames()), confidence, reason);
        };
    }

    /** 词面打分：重合度 + 正负样例修正。 */
    private double score(Set<String> questionTokens, Candidate candidate) {
        StringBuilder corpus = new StringBuilder()
                .append(candidate.name()).append(' ')
                .append(candidate.description()).append(' ')
                .append(String.join(" ", candidate.keywords())).append(' ')
                .append(String.join(" ", candidate.positiveExamples()));

        Set<String> candidateTokens = tokenize(corpus.toString());
        if (candidateTokens.isEmpty()) return 0;

        Set<String> overlap = new LinkedHashSet<>(questionTokens);
        overlap.retainAll(candidateTokens);
        if (overlap.size() < 2) return 0;   // 仅单字重合属于噪声

        // 分母饱和：中文二元组会让长问句 token 数虚高
        int denominator = Math.min(Math.max(questionTokens.size(), 4), 12);
        double base = (double) overlap.size() / denominator;

        for (String example : candidate.positiveExamples()) {
            Set<String> exampleTokens = tokenize(example);
            if (exampleTokens.isEmpty()) continue;
            Set<String> hit = new LinkedHashSet<>(questionTokens);
            hit.retainAll(exampleTokens);
            if ((double) hit.size() / exampleTokens.size() > 0.6) { base += 0.35; break; }
        }
        for (String example : candidate.negativeExamples()) {
            Set<String> exampleTokens = tokenize(example);
            if (exampleTokens.isEmpty()) continue;
            Set<String> hit = new LinkedHashSet<>(questionTokens);
            hit.retainAll(exampleTokens);
            if ((double) hit.size() / exampleTokens.size() > 0.6) { base -= 0.4; break; }
        }
        return Math.max(0, Math.min(base, 1.0));
    }

    /** 中文按单字 + 二元组切分，英文按单词。 */
    Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String lower = text.toLowerCase();
        Set<String> tokens = new LinkedHashSet<>();

        Matcher words = ASCII_WORD.matcher(lower);
        while (words.find()) tokens.add(words.group());

        Matcher chars = CJK.matcher(lower);
        while (chars.find()) tokens.add(chars.group());

        Matcher runs = CJK_RUN.matcher(lower);
        while (runs.find()) {
            String run = runs.group();
            for (int i = 0; i + 2 <= run.length(); i++) tokens.add(run.substring(i, i + 2));
        }
        tokens.removeAll(STOPWORDS);
        return tokens;
    }

    /** 识别问题里的业务实体 ID。 */
    public Map<String, String> detectEntities(String question) {
        Map<String, String> found = new LinkedHashMap<>();
        ENTITY_PATTERNS.forEach((field, pattern) -> {
            Matcher matcher = pattern.matcher(question);
            if (matcher.find()) found.put(field, matcher.group(1).toUpperCase());
        });
        return found;
    }

    /**
     * 参数抽取。只产出目标能力 schema 中真实存在的字段 ——
     * 否则会把 employee_id 塞给不接受它的工具，导致下游直接报错。
     */
    public Map<String, Object> extractArguments(String question, List<String> required,
                                                List<String> accepted) {
        Map<String, Object> args = new LinkedHashMap<>(detectEntities(question));

        for (String textField : List.of("query", "keyword")) {
            boolean wanted = (required != null && required.contains(textField))
                    || (accepted != null && accepted.contains(textField));
            if (wanted) args.putIfAbsent(textField, question.trim());
        }

        if (accepted != null && !accepted.isEmpty()) {
            args.keySet().retainAll(accepted);
        } else if (required != null && !required.isEmpty()) {
            args.keySet().retainAll(required);
        }
        return args;
    }

    private String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    public List<String> entityFields() {
        return new ArrayList<>(ENTITY_PATTERNS.keySet());
    }
}
