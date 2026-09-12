package com.rsi.agent.agent.transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.rsi.agent.agent.AgentEvent;
import com.rsi.agent.agent.CapabilityRegistry;
import com.rsi.agent.agent.CapabilitySnapshot;
import com.rsi.agent.agent.CapabilitySnapshot.ToolEntry;
import com.rsi.agent.agent.Prompts;
import com.rsi.agent.agent.RouteCandidates;
import com.rsi.agent.agent.RoutePlan;
import com.rsi.agent.agent.guard.AnswerVerifier;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.llm.LlmResult;
import com.rsi.agent.llm.LlmRouter;
import com.rsi.agent.llm.RuleEngine;
import com.rsi.agent.mcp.ToolResult;
import com.rsi.agent.observability.TraceCollector;
import com.rsi.agent.observability.TraceContext;
import org.springframework.stereotype.Component;

/**
 * 本地子 Agent：注册的只是一份配置（prompt + 工具白名单），ReAct 循环跑在本进程。
 *
 * <p>这是平台的内置形态，也是唯一能<b>结构性</b>保证工具边界的形态 ——
 * 候选集只含自己的工具，越界与二次分发在类型层面就不可能发生。
 * 远程 A2A 子 Agent 拿不到这个保证，见 {@link A2aAgentTransport}。
 */
@Component
public class LocalAgentTransport implements AgentTransport {

    private final CapabilityRegistry registry;
    private final LlmRouter llmRouter;
    private final RuleEngine ruleEngine;

    public LocalAgentTransport(CapabilityRegistry registry, LlmRouter llmRouter,
                               RuleEngine ruleEngine) {
        this.registry = registry;
        this.llmRouter = llmRouter;
        this.ruleEngine = ruleEngine;
    }

    @Override
    public String transport() { return "local"; }

    @Override
    public DelegationContracts.Result delegate(DelegationContracts.Request request,
                                               DelegationContracts.Host host,
                                               Consumer<AgentEvent> emit) {
        long started = System.nanoTime();
        SubAgentEntity agent = request.agent();
        List<ToolEntry> tools = registry.agentTools(request.snapshot(), agent);

        emit.accept(AgentEvent.of("agent_start",
                "agent", agent.getName(), "display_name", agent.getDisplayName(),
                "transport", "local",
                "tools", tools.stream().map(ToolEntry::qualifiedName).toList(),
                "budget", agent.getMaxIterations()));

        // 子 Agent 的候选集只含自己的工具：越界与二次分发在这里被结构性杜绝
        RouteCandidates candidates = registry.agentCandidates(tools);
        CapabilitySnapshot subSnapshot = CapabilitySnapshot.ofTools(
                tools, request.snapshot().totalToolCount());

        String system = Prompts.subagentSystem(agent.getSystemPrompt())
                + "\n\n" + candidates.describe(25);

        List<String> subEvidence = new ArrayList<>();
        int budget = agent.getMaxIterations() <= 0 ? 6 : agent.getMaxIterations();
        boolean degraded = host.degraded();

        for (int step = 1; step <= budget; step++) {
            StringBuilder userPrompt = new StringBuilder("待办任务：").append(request.task()).append('\n');
            if (!subEvidence.isEmpty()) {
                userPrompt.append("\n已获取证据：\n");
                subEvidence.forEach(item -> userPrompt.append("- ").append(item).append('\n'));
            }
            userPrompt.append("\n请决定下一步：调用工具（route_type=tool）、")
                    .append("已可作答（route_type=answer，把结论写进 message）或需要澄清（clarify）。");

            // 子 Agent 不再受置信度阈值二次拦截 —— 领域边界已由上层确定
            LlmResult result = llmRouter.route(system, userPrompt.toString(),
                    request.task(), candidates, false);
            RoutePlan plan = result.plan() != null ? result.plan()
                    : ruleEngine.route(request.task(), candidates, false);

            TraceCollector collector = TraceContext.peek();
            if (collector != null) {
                collector.addTokens(result.promptTokens(), result.completionTokens());
                if (result.degraded()) collector.markDegraded();
            }
            if (result.degraded()) {
                degraded = true;
                host.markDegraded();
            }

            if (!(plan instanceof RoutePlan.CallTool call)) {
                String message = switch (plan) {
                    case RoutePlan.Reply reply -> reply.message();
                    case RoutePlan.Clarify clarify -> clarify.message();
                    default -> "子 Agent 未产出结论。";
                };
                subEvidence.add("[%s] 结论：%s".formatted(agent.getName(), message));
                break;
            }

            Optional<ToolEntry> entry = subSnapshot.tool(call.tool());
            if (entry.isEmpty()) {
                emit.accept(AgentEvent.of("guard", "kind", "out_of_scope_tool",
                        "message", "子 Agent %s 请求了 allowlist 之外的工具 %s，已拦截。"
                                .formatted(agent.getName(), call.tool())));
                subEvidence.add("[%s] 尝试使用领域外工具 `%s`，已被工具边界拦截。"
                        .formatted(agent.getName(), call.tool()));
                break;
            }

            Map<String, Object> args = new LinkedHashMap<>(call.arguments());
            if (args.isEmpty()) {
                Map<String, Object> schema = entry.get().tool().getInputSchema();
                args.putAll(ruleEngine.extractArguments(request.task(),
                        CapabilityRegistry.requiredOf(schema), CapabilityRegistry.propertiesOf(schema)));
            }

            List<String> missing = AnswerVerifier.missingRequired(entry.get().tool().getInputSchema(), args);
            if (!missing.isEmpty()) {
                subEvidence.add("[%s] 缺少参数 %s，无法调用 `%s`。".formatted(
                        agent.getName(), String.join(", ", missing), entry.get().qualifiedName()));
                break;
            }

            if (host.guardBlocked(entry.get().qualifiedName(), args)) break;

            DelegationContracts.Approval approval = host.approval(entry.get(), args);
            if (approval == DelegationContracts.Approval.PENDING
                    || approval == DelegationContracts.Approval.REJECTED) {
                // 宿主已在 State 上记录挂起/拒绝，这里不再发 agent_end
                return DelegationContracts.Result.suspended(subEvidence, subEvidence.size(),
                        elapsed(started));
            }

            ToolResult toolResult = host.invokeTool(entry.get(), args,
                    Map.of("agent", agent.getName()));
            subEvidence.add("[%s] `%s` → %s".formatted(agent.getName(),
                    entry.get().qualifiedName(), toolResult.summary(400)));

            // 规则引擎模式下模型无法判断证据是否充分，成功取得结果后即收敛，避免空转
            if (degraded && toolResult.success()) break;
        }

        emit.accept(AgentEvent.of("agent_end", "agent", agent.getName(),
                "transport", "local", "steps", subEvidence.size()));

        return DelegationContracts.Result.completed(subEvidence, subEvidence.size(),
                elapsed(started), "", "");
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
