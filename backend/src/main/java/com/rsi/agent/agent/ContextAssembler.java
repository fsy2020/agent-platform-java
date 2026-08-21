package com.rsi.agent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.llm.LlmResult;
import com.rsi.agent.llm.LlmRouter;
import com.rsi.agent.security.Identity;
import org.springframework.stereotype.Service;

/**
 * 上下文工程：分层装配 + 阈值触发的「保留头尾、压缩中段」。
 *
 * <p>分层顺序即稳定性顺序，越靠前越不变，以最大化 prefix cache 命中：
 * <pre>
 *   L1 稳定层  System Prompt / 工具协议 / 行为约束   —— 整会话不变
 *   L2 任务层  身份权限 + 当前可见能力清单 + 历史摘要 —— 任务变化时重装配
 *   L3 会话层  最近对话 + 任务计划 + 工具结果摘要     —— 每轮变化
 * </pre>
 */
@Service
public class ContextAssembler {

    private static final int HEAD_KEEP = 2;
    private static final int TAIL_KEEP = 6;
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final String SUMMARY_SYSTEM = """
            你是会话压缩器。把中段对话压缩为结构化摘要，严格保留：
            1) 用户的原始诉求与后续变更；2) 已完成的步骤及其结果；3) 尚未解决的问题；
            4) 关键工具结果（编号、金额、日期、状态）；5) 审批状态。
            丢弃寒暄与重复内容。用中文，不超过 400 字，使用分条列表。
            """;

    /** 一轮对话。 */
    public record Turn(String role, String content) {}

    /** 压缩结果。 */
    public record Compression(boolean compressed, String summary, List<Turn> keptTurns,
                              int droppedCount, int beforeTokens, int afterTokens) {}

    /** 装配好的上下文。 */
    public record Assembled(String text, int tokens, boolean compressed, String summary,
                            List<Turn> turns) {}

    private final LlmRouter llmRouter;
    private final AgentProperties.Runtime config;

    public ContextAssembler(LlmRouter llmRouter, AgentProperties properties) {
        this.llmRouter = llmRouter;
        this.config = properties.runtime();
    }

    /** 粗略 token 估算：中文按 1 字≈1 token，其余按 4 字符≈1 token。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long cjk = CJK.matcher(text).results().count();
        long rest = text.length() - cjk;
        return (int) (cjk + Math.max(1, rest / 4));
    }

    /**
     * 装配上下文，必要时触发压缩。
     *
     * @param capabilities 已裁剪的能力清单渲染文本（L2）
     */
    public Assembled assemble(Identity identity, String capabilities, String summary,
                              List<Turn> turns, List<String> evidence) {
        String stable = Prompts.supervisorSystem();
        String task = taskLayer(identity, capabilities, summary);
        String session = sessionLayer(turns, evidence);

        int tokens = estimateTokens(stable) + estimateTokens(task) + estimateTokens(session);
        boolean compressed = false;
        String workingSummary = summary;
        List<Turn> workingTurns = turns;

        if (tokens > config.contextWindowTokens() * config.compressTriggerRatio()) {
            Compression compression = compress(turns, summary);
            if (compression.compressed()) {
                compressed = true;
                workingSummary = compression.summary();
                workingTurns = compression.keptTurns();
                task = taskLayer(identity, capabilities, workingSummary);
                session = sessionLayer(workingTurns, evidence);
                tokens = estimateTokens(stable) + estimateTokens(task) + estimateTokens(session);
            }
        }

        String text = stable + "\n\n" + task + (session.isBlank() ? "" : "\n\n" + session);
        return new Assembled(text, tokens, compressed, workingSummary, workingTurns);
    }

    private String taskLayer(Identity identity, String capabilities, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前调用方\n");
        sb.append("- 用户：").append(identity.displayName())
                .append("（角色 ").append(identity.role())
                .append("，租户 ").append(identity.tenantId()).append("）\n");
        sb.append("- 已授权 scope：")
                .append(identity.scopes().contains("*") ? "全部（管理员）"
                        : String.join(", ", identity.sortedScopes()))
                .append('\n');
        sb.append('\n').append(capabilities);
        if (summary != null && !summary.isBlank()) {
            sb.append("\n\n## 历史会话摘要（已压缩）\n").append(summary);
        }
        return sb.toString();
    }

    private String sessionLayer(List<Turn> turns, List<String> evidence) {
        StringBuilder sb = new StringBuilder();
        if (turns != null && !turns.isEmpty()) {
            sb.append("## 最近对话\n");
            for (Turn turn : turns) {
                sb.append("- ").append("user".equals(turn.role()) ? "用户" : "助手")
                        .append("：").append(turn.content()).append('\n');
            }
        }
        if (evidence != null && !evidence.isEmpty()) {
            sb.append("\n## 已获取的工具执行证据\n");
            for (int i = 0; i < evidence.size(); i++) {
                sb.append(i + 1).append(". ").append(evidence.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 保留头尾、压缩中段。
     *
     * <p>保留用户最新诉求、已完成步骤、待处理问题、关键工具结果、审批状态；
     * 丢弃中段冗余。LLM 不可用时退化为「抽取含业务实体的句子」的确定性摘要 ——
     * 压缩本身不能依赖一个可能挂掉的服务。
     */
    public Compression compress(List<Turn> turns, String existingSummary) {
        int before = estimateTokens(existingSummary)
                + turns.stream().mapToInt(t -> estimateTokens(t.content())).sum();

        if (turns.size() <= HEAD_KEEP + TAIL_KEEP) {
            return new Compression(false, existingSummary, turns, 0, before, before);
        }

        List<Turn> head = turns.subList(0, HEAD_KEEP);
        List<Turn> tail = turns.subList(turns.size() - TAIL_KEEP, turns.size());
        List<Turn> middle = turns.subList(HEAD_KEEP, turns.size() - TAIL_KEEP);

        StringBuilder middleText = new StringBuilder();
        for (Turn turn : middle) {
            middleText.append("user".equals(turn.role()) ? "用户" : "助手")
                    .append("：").append(turn.content()).append('\n');
        }

        String summary = summarize(middleText.toString(), existingSummary);

        List<Turn> kept = new ArrayList<>(head);
        kept.addAll(tail);
        int after = estimateTokens(summary)
                + kept.stream().mapToInt(t -> estimateTokens(t.content())).sum();

        return new Compression(true, summary, kept, middle.size(), before, after);
    }

    private String summarize(String middleText, String existingSummary) {
        String user = existingSummary == null || existingSummary.isBlank()
                ? middleText
                : "已有摘要：\n" + existingSummary + "\n\n新增待压缩对话：\n" + middleText;

        LlmResult result = llmRouter.complete(SUMMARY_SYSTEM, user);
        if (result.text() != null && !result.text().isBlank()) {
            return result.text().trim();
        }
        return heuristicSummary(middleText, existingSummary);
    }

    /** 确定性兜底摘要：抽取带业务实体的句子。 */
    private String heuristicSummary(String middleText, String existingSummary) {
        Pattern keyLine = Pattern.compile("(E\\d{4}|INC-|RB-|C-\\d{3}|SO-|已提交|已创建|失败|待审批|金额|工单)");
        StringBuilder body = new StringBuilder();
        int kept = 0;
        for (String line : middleText.split("\n")) {
            if (kept >= 12) break;
            if (keyLine.matcher(line).find()) {
                body.append("- ").append(line.trim()).append('\n');
                kept++;
            }
        }
        if (kept == 0) body.append("- （中段对话无关键业务实体，已省略）\n");

        String prefix = existingSummary == null || existingSummary.isBlank() ? "" : existingSummary + "\n";
        return prefix + "【中段压缩摘要】\n" + body;
    }
}
