package com.rsi.agent.agent.guard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 循环治理。
 *
 * <p>通过「工具调用签名 + 结果摘要」识别两类循环：
 * <ol>
 *   <li>相同参数反复失败 —— 再试第三次也不会成功，只是放大下游压力</li>
 *   <li>只读工具无进展重复调用 —— 拿到的还是同一份数据，模型在原地打转</li>
 * </ol>
 * 超过阈值即阻断，并给模型一条明确的策略调整提示，而不是简单报错。
 */
public class LoopGuard {

    /** 阻断判定结果。blocked=false 时其余字段无意义。 */
    public record Verdict(boolean blocked, String kind, String message, String hint) {
        public static Verdict pass() { return new Verdict(false, "", "", ""); }
    }

    private final int threshold;
    private final Map<String, Integer> calls = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, String> resultDigests = new HashMap<>();

    public LoopGuard(int threshold) {
        this.threshold = threshold;
    }

    /** 调用签名：工具名 + 规范化后的参数。参数顺序不同不应算作不同调用。 */
    public static String signature(String tool, Map<String, Object> args) {
        String canonical = tool + "|" + new TreeMap<>(args == null ? Map.of() : args);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(canonical.hashCode());
        }
    }

    public Verdict check(String tool, Map<String, Object> args) {
        String key = signature(tool, args);

        int failed = failures.getOrDefault(key, 0);
        if (failed >= threshold - 1) {
            return new Verdict(true, "repeated_failure",
                    "工具 `%s` 使用相同参数已连续失败 %d 次，已阻断以避免死循环。".formatted(tool, failed),
                    "请更换执行策略：改用其他工具/Skill、补齐缺失参数，或向用户说明当前阻塞点。");
        }

        int called = calls.getOrDefault(key, 0);
        if (called >= threshold) {
            return new Verdict(true, "no_progress",
                    "工具 `%s` 相同参数已重复调用 %d 次且无新进展，已阻断。".formatted(tool, called),
                    "重复调用不会带来新信息，请基于已有结果给出结论或换一条执行路径。");
        }
        return Verdict.pass();
    }

    public void record(String tool, Map<String, Object> args, boolean success, String resultSummary) {
        String key = signature(tool, args);
        calls.merge(key, 1, Integer::sum);
        if (success) {
            failures.put(key, 0);
            resultDigests.put(key, String.valueOf(resultSummary == null ? 0 : resultSummary.hashCode()));
        } else {
            failures.merge(key, 1, Integer::sum);
        }
    }

    public Map<String, Object> snapshot() {
        long repeated = failures.values().stream().filter(v -> v > 0).count();
        return Map.of(
                "distinct_calls", calls.size(),
                "total_calls", calls.values().stream().mapToInt(Integer::intValue).sum(),
                "repeated_failures", repeated);
    }
}
