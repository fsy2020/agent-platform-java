package com.rsi.agent.agent.transport;

import java.util.List;
import java.util.Map;

import com.rsi.agent.agent.CapabilitySnapshot;
import com.rsi.agent.agent.CapabilitySnapshot.ToolEntry;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.mcp.ToolResult;
import com.rsi.agent.security.Identity;

/** 一次子 Agent 委派的输入、输出与回调契约。对标 {@code TurnContracts}。 */
public final class DelegationContracts {

    private DelegationContracts() {}

    /** 续发到既有远程任务。审批放行后的恢复走这条路径，而不是新建任务。 */
    public record RemoteTask(String taskId, String contextId, String reply) {}

    public record Request(
            SubAgentEntity agent,
            String task,
            Identity identity,
            /** 本地委派用于裁剪工具；远程委派忽略（远端有自己的能力集）。 */
            CapabilitySnapshot snapshot,
            String sessionId,
            String traceId,
            RemoteTask resume
    ) {
        public boolean isResume() { return resume != null; }
    }

    /**
     * 委派结果。
     *
     * <p>{@code evidence} 是唯一回传给父 Agent 的东西 —— 本地与远程在这一点上
     * 完全一致，因此 Supervisor 的汇总节点无需感知子 Agent 跑在哪里。
     */
    public record Result(
            /** completed / suspended / input_required / failed */
            String status,
            List<String> evidence,
            int steps,
            String errorType,
            String errorMessage,
            long latencyMs,
            /** 远程任务标识，用于审批恢复；本地委派为空串。 */
            String remoteTaskId,
            String remoteContextId,
            /** input_required 时远端要问用户的话。 */
            String prompt
    ) {
        public boolean needsInput() { return "input_required".equals(status); }

        /** 宿主已在 State 上记录了挂起/拒绝，运行时只需合并证据后返回。 */
        public boolean suspended() { return "suspended".equals(status); }

        public boolean failed() { return "failed".equals(status); }

        public static Result completed(List<String> evidence, int steps, long latencyMs,
                                       String taskId, String contextId) {
            return new Result("completed", evidence, steps, "", "", latencyMs,
                    taskId == null ? "" : taskId, contextId == null ? "" : contextId, "");
        }

        public static Result inputRequired(List<String> evidence, int steps, long latencyMs,
                                           String taskId, String contextId, String prompt) {
            return new Result("input_required", evidence, steps, "", "", latencyMs,
                    taskId == null ? "" : taskId, contextId == null ? "" : contextId,
                    prompt == null ? "" : prompt);
        }

        public static Result failed(String errorType, String errorMessage, List<String> evidence,
                                    int steps, long latencyMs) {
            return new Result("failed", evidence, steps, errorType, errorMessage, latencyMs,
                    "", "", "");
        }

        /** 本地委派撞上审批关卡：宿主已挂起或已拒绝，不再产生 agent_end。 */
        public static Result suspended(List<String> evidence, int steps, long latencyMs) {
            return new Result("suspended", evidence, steps, "", "", latencyMs, "", "", "");
        }
    }

    /**
     * 传输层回调宿主。
     *
     * <p>护栏、审批、工具调用与指标累加都是<b>平台级</b>关注点，必须由
     * {@code AgentRuntime} 统一持有 —— 传输层只负责"决定调什么"，
     * 不允许绕过这三道关卡自己发起调用。
     *
     * <p>做成方法参数而非注入的 Bean，是为了避开
     * {@code AgentRuntime → AgentGateway → LocalAgentTransport → AgentRuntime} 的循环依赖。
     */
    public interface Host {
        /** 当前是否处于规则引擎降级模式。 */
        boolean degraded();

        /**
         * 回写降级状态。
         *
         * <p>子 Agent 内部探测到模型不可用时必须让父层知道 —— Supervisor 的
         * 早收敛判断（拿到证据即停，避免规则引擎空转）依赖这个标志。
         */
        void markDegraded();

        /** 循环护栏。返回 true 表示已拦截，调用方应立即收敛。 */
        boolean guardBlocked(String qualifiedName, Map<String, Object> args);

        /** 人工审批关卡。 */
        Approval approval(ToolEntry entry, Map<String, Object> args);

        /** 统一工具调用入口：熔断、重试、指标、事件推送都在宿主侧完成。 */
        ToolResult invokeTool(ToolEntry entry, Map<String, Object> args,
                              Map<String, Object> extraEventFields);
    }

    /** 审批结论。PENDING 表示已挂起等待人工，本轮必须中止。 */
    public enum Approval {
        NOT_REQUIRED, APPROVED, PENDING, REJECTED
    }
}
