package com.rsi.agent.agent.guard;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.rsi.agent.agent.CapabilityRegistry;

/**
 * 防幻觉校验：禁止在未真正执行写操作时声称任务已完成。
 *
 * <p>正则只匹配「对业务对象的写操作完成声明」。泛化的「已完成」不计入 ——
 * 否则会误伤「本轮的工具调用已完成如下」这类陈述性表述（这是实测中修掉的假阳性）。
 */
public final class AnswerVerifier {

    private AnswerVerifier() {}

    private static final Pattern COMPLETION_CLAIM = Pattern.compile(
            "(已(经)?(为你|帮你)?(成功)?(提交|创建|新建|重置|更新|发起|登记|办理)"
                    + "|(工单|报销单|申请单|请假申请)\\s*(号|编号)?\\s*[:：]?\\s*[A-Z]{2,}-?\\d+)");

    /** 校验结论。ok=false 时用 corrected 覆盖模型原答复。 */
    public record Verdict(boolean ok, String reason, String corrected) {
        public static Verdict pass() { return new Verdict(true, "", ""); }
    }

    public static Verdict verify(String answer, boolean hadSuccessfulWrite, List<String> evidence) {
        if (answer == null || answer.isBlank()) {
            return new Verdict(false, "empty_answer",
                    "抱歉，本轮未能生成有效答复，请重试或补充信息。");
        }

        if (COMPLETION_CLAIM.matcher(answer).find() && !hadSuccessfulWrite) {
            StringBuilder corrected = new StringBuilder(
                    "我还**没有**真正执行该写操作，因此不能确认任务已完成。\n\n");
            if (evidence != null && !evidence.isEmpty()) {
                corrected.append("目前已获取的信息：\n");
                evidence.stream().limit(5).forEach(item -> corrected.append("- ").append(item).append('\n'));
            }
            corrected.append("\n请确认后我再发起实际操作，或告知缺失的参数。");
            return new Verdict(false, "unverified_completion_claim", corrected.toString().trim());
        }
        return Verdict.pass();
    }

    /** 参数校验：缺失必填项时触发澄清，而不是带着空值硬调。 */
    public static List<String> missingRequired(Map<String, Object> schema, Map<String, Object> args) {
        List<String> required = CapabilityRegistry.requiredOf(schema);
        return required.stream()
                .filter(key -> {
                    Object value = args == null ? null : args.get(key);
                    if (value == null) return true;
                    if (value instanceof String text) return text.isBlank();
                    if (value instanceof Map<?, ?> map) return map.isEmpty();
                    if (value instanceof List<?> list) return list.isEmpty();
                    return false;
                })
                .toList();
    }
}
