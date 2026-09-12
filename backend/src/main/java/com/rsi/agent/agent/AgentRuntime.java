package com.rsi.agent.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.rsi.agent.agent.CapabilitySnapshot.ToolEntry;
import com.rsi.agent.agent.guard.AnswerVerifier;
import com.rsi.agent.agent.guard.LoopGuard;
import com.rsi.agent.agent.transport.DelegationContracts;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.domain.ApprovalEntity;
import com.rsi.agent.domain.McpToolEntity;
import com.rsi.agent.domain.SkillEntity;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.llm.LlmResult;
import com.rsi.agent.llm.LlmRouter;
import com.rsi.agent.llm.RuleEngine;
import com.rsi.agent.mcp.McpGateway;
import com.rsi.agent.mcp.ToolResult;
import com.rsi.agent.observability.TraceCollector;
import com.rsi.agent.observability.TraceContext;
import com.rsi.agent.repository.ApprovalRepository;
import com.rsi.agent.repository.McpToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent Runtime —— 融合式编排。
 *
 * <p>主循环是 ReAct 风格：简单任务直接走工具闭环；复杂任务才 delegate 给子 Agent。
 * 低成本任务不引入额外编排开销，高复杂任务保留拆解、隔离与预算控制。
 *
 * <pre>
 *   prepare ──> route ──> execute ──> route（带着新证据重新决策）
 *                 │          ├──> finalize（证据够了 / 达上限 / 被护栏阻断）
 *                 │          └──> 挂起（等待人工审批）
 *                 └──> finalize（answer / clarify 无需执行）
 * </pre>
 */
@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final CapabilityRegistry registry;
    private final ContextAssembler contextAssembler;
    private final LlmRouter llmRouter;
    private final RuleEngine ruleEngine;
    private final McpGateway gateway;
    private final AgentGateway agentGateway;
    private final McpToolRepository toolRepository;
    private final ApprovalRepository approvalRepository;
    private final AgentProperties.Runtime config;
    private final AgentProperties.A2a a2aConfig;

    public AgentRuntime(CapabilityRegistry registry, ContextAssembler contextAssembler,
                        LlmRouter llmRouter, RuleEngine ruleEngine, McpGateway gateway,
                        AgentGateway agentGateway, McpToolRepository toolRepository,
                        ApprovalRepository approvalRepository, AgentProperties properties) {
        this.registry = registry;
        this.contextAssembler = contextAssembler;
        this.llmRouter = llmRouter;
        this.ruleEngine = ruleEngine;
        this.gateway = gateway;
        this.agentGateway = agentGateway;
        this.toolRepository = toolRepository;
        this.approvalRepository = approvalRepository;
        this.config = properties.runtime();
        this.a2aConfig = properties.a2a();
    }

    /** 一次 turn 的可变状态。集中在一个对象里，避免十几个参数在方法间传递。 */
    private static final class State {
        final List<String> evidence = new ArrayList<>();
        final Map<String, String> approvals = new HashMap<>();
        final List<String> routeHistory = new ArrayList<>();
        LoopGuard guard;
        int iterations;
        int toolCalls;
        int toolFailures;
        boolean hadSuccessfulWrite;
        boolean degraded;
        String finalAnswer;
        String status = "running";
        Map<String, Object> pendingApproval;
    }

    // ------------------------------------------------------------------ 入口

    public TurnContracts.Result run(TurnContracts.Request request, Consumer<AgentEvent> emit) {
        TraceCollector collector = new TraceCollector(request.traceId());
        TraceContext.set(collector);

        State state = new State();
        state.guard = new LoopGuard(config.loopRepeatThreshold());
        state.degraded = !llmRouter.available();

        try {
            // ---------- prepare：能力裁剪 + 上下文装配 ----------
            RouteCandidates candidates = registry.supervisorCandidates(request.snapshot());
            ContextAssembler.Assembled context = contextAssembler.assemble(
                    request.identity(), candidates.describe(40), request.summary(),
                    request.history(), state.evidence);

            emit.accept(AgentEvent.of("context",
                    "compressed", context.compressed(),
                    "context_tokens", context.tokens(),
                    "visible_tools", candidates.tools().size(),
                    "visible_skills", candidates.skills().size(),
                    "visible_agents", candidates.agents().size(),
                    "total_tools", request.snapshot().totalToolCount()));

            collector.addSpan("上下文装配", "context",
                    Map.of("turns", request.history().size()),
                    Map.of("tokens", context.tokens(), "compressed", context.compressed(),
                            "visible_tools", candidates.visibleCount()),
                    "success", "", 0);

            RoutePlan plan = null;

            if (request.isResume()) {
                // ---------- resume：从执行节点直接续跑，不重新路由 ----------
                plan = request.resume().plan();
                state.approvals.put(request.resume().signature(), request.resume().decision());
                execute(plan, request, state, emit);
            } else {
                // ---------- route ⇄ execute 主循环 ----------
                while (state.iterations < config.maxIterations()) {
                    plan = route(request, context, candidates, state, emit);
                    state.iterations++;
                    state.routeHistory.add(plan.type() + ":" + plan.target());

                    if (!plan.needsExecution()) break;

                    execute(plan, request, state, emit);

                    if ("waiting_approval".equals(state.status)) {
                        return finish(state, plan, context, candidates, request, collector);
                    }
                    if (state.finalAnswer != null || "blocked".equals(state.status)) break;

                    // 规则引擎无法判断证据是否充分：取得证据后立即收敛，避免空转
                    if (state.degraded && !state.evidence.isEmpty()) break;

                    // 同一路径连续两次被选中且无新进展 → 收敛，交给汇总节点
                    int size = state.routeHistory.size();
                    if (size >= 2 && state.routeHistory.get(size - 1).equals(state.routeHistory.get(size - 2))) {
                        break;
                    }
                }
            }

            // ---------- finalize ----------
            String answer = finalize(plan, state, request, emit, collector);
            state.finalAnswer = answer;
            return finish(state, plan, context, candidates, request, collector);

        } catch (Exception e) {
            log.error("Agent 执行失败", e);
            emit.accept(AgentEvent.of("error", "message", e.getClass().getSimpleName() + ": " + e.getMessage()));
            state.status = "failed";
            state.finalAnswer = "执行过程中发生异常：" + e.getMessage();
            return finish(state, null, null, null, request, collector);
        } finally {
            TraceContext.clear();
        }
    }

    // ------------------------------------------------------------------ 路由

    private RoutePlan route(TurnContracts.Request request, ContextAssembler.Assembled context,
                            RouteCandidates candidates, State state, Consumer<AgentEvent> emit) {
        emit.accept(AgentEvent.of("status", "phase", "routing",
                "message", "Supervisor 正在识别意图并选择执行路径…"));

        String userPrompt = buildRoutePrompt(request.question(), state);
        LlmResult result = llmRouter.route(context.text(), userPrompt, request.question(),
                candidates, true);

        RoutePlan plan = result.plan() != null ? result.plan()
                : ruleEngine.route(request.question(), candidates, true);

        TraceCollector collector = TraceContext.peek();
        if (collector != null) {
            collector.modelUsed(result.model());
            if (result.fallbackUsed()) collector.markFallback();
            if (result.degraded()) collector.markDegraded();
            collector.addTokens(result.promptTokens(), result.completionTokens());
            collector.addSpan("路由决策 → " + plan.type(), "route",
                    Map.of("question", request.question(),
                            "candidates", Map.of("agents", candidates.agents().size(),
                                    "skills", candidates.skills().size(),
                                    "tools", candidates.tools().size())),
                    Map.of("route_type", plan.type(), "target", plan.target(),
                            "confidence", plan.confidence(), "reason", plan.reason()),
                    "success", "", 0);
        }
        state.degraded = state.degraded || result.degraded();

        boolean lowConfidence = plan.confidence() < config.routeConfidenceThreshold();
        if (lowConfidence && plan.needsExecution()) {
            emit.accept(AgentEvent.of("guard", "kind", "low_confidence",
                    "message", "路由置信度 %.2f 低于阈值 %.2f，已记录用于 description 与 few-shot 优化。"
                            .formatted(plan.confidence(), config.routeConfidenceThreshold())));
        }

        emit.accept(AgentEvent.of("route",
                "route_type", plan.type(), "target", plan.target(),
                "confidence", plan.confidence(), "reason", plan.reason(),
                "degraded", result.degraded(), "model", result.model(),
                "low_confidence", lowConfidence));

        return plan;
    }

    private String buildRoutePrompt(String question, State state) {
        StringBuilder sb = new StringBuilder("用户当前请求：").append(question).append('\n');
        if (!state.evidence.isEmpty()) {
            sb.append("\n本轮已获取的工具执行证据：\n");
            for (int i = 0; i < state.evidence.size(); i++) {
                sb.append(i + 1).append(". ").append(state.evidence.get(i)).append('\n');
            }
            sb.append("\n如果证据已足够回答用户，请选择 answer；否则继续选择下一步执行路径。\n");
        }
        if (state.iterations >= config.maxIterations() - 2) {
            sb.append("\n注意：已执行 ").append(state.iterations)
                    .append(" 轮，接近上限，请尽快收敛为 answer 或 clarify。\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 执行分发

    /**
     * 执行分发。
     *
     * <p>switch 对密封接口做<b>模式匹配</b>，编译器保证穷尽 —— 将来新增一种
     * 执行路径而这里忘了处理，是编译错误而不是线上的静默 default 分支。
     */
    private void execute(RoutePlan plan, TurnContracts.Request request, State state,
                         Consumer<AgentEvent> emit) {
        switch (plan) {
            case RoutePlan.CallTool call -> executeTool(call, request, state, emit);
            case RoutePlan.RunSkill skill -> executeSkill(skill, request, state, emit);
            case RoutePlan.Delegate delegate -> executeAgent(delegate, request, state, emit);
            case RoutePlan.Reply ignored -> { /* 无需执行 */ }
            case RoutePlan.Clarify ignored -> { /* 无需执行 */ }
        }
    }

    // ------------------------------------------------------------------ 工具

    private void executeTool(RoutePlan.CallTool call, TurnContracts.Request request,
                             State state, Consumer<AgentEvent> emit) {
        Optional<ToolEntry> found = request.snapshot().tool(call.tool());
        if (found.isEmpty()) {
            String message = "工具 `%s` 不存在或当前身份无权访问。".formatted(call.tool());
            emit.accept(AgentEvent.of("guard", "kind", "tool_not_found", "message", message));
            state.evidence.add("[路由错误] " + message);
            return;
        }
        ToolEntry entry = found.get();
        Map<String, Object> args = new LinkedHashMap<>(call.arguments());

        // 参数校验：缺必填项转澄清，而不是带着空值硬调
        List<String> missing = AnswerVerifier.missingRequired(entry.tool().getInputSchema(), args);
        if (!missing.isEmpty()) {
            String message = "调用 `%s` 缺少必填参数：%s。请补充后我再执行。"
                    .formatted(entry.qualifiedName(), String.join(", ", missing));
            emit.accept(AgentEvent.of("guard", "kind", "missing_arguments", "message", message));
            state.finalAnswer = message;
            return;
        }

        if (guardBlocked(entry.qualifiedName(), args, state, emit)) return;

        if (requiresApproval(entry) && !passApproval(entry.qualifiedName(), args, state, emit,
                request, call, "tool", entry.qualifiedName(), entry)) {
            return;
        }

        invokeTool(entry, args, state, emit, Map.of());
    }

    /** 统一的工具调用：护栏记录、指标累加、事件推送都在这里，避免三处重复。 */
    private ToolResult invokeTool(ToolEntry entry, Map<String, Object> args, State state,
                                  Consumer<AgentEvent> emit, Map<String, Object> extraEventFields) {
        Map<String, Object> startEvent = new LinkedHashMap<>(extraEventFields);
        startEvent.put("tool", entry.qualifiedName());
        startEvent.put("args", args);
        startEvent.put("risk_level", entry.tool().getRiskLevel());
        emit.accept(AgentEvent.of("tool_start", startEvent));

        ToolResult result = gateway.call(entry.server(), entry.tool().getName(), args);
        recordToolMetrics(entry.tool(), result);
        state.guard.record(entry.qualifiedName(), args, result.success(), result.summary());
        state.toolCalls++;
        if (!result.success()) {
            state.toolFailures++;
        } else if (!entry.tool().isReadOnly()) {
            state.hadSuccessfulWrite = true;
        }

        Map<String, Object> endEvent = new LinkedHashMap<>(extraEventFields);
        endEvent.put("tool", entry.qualifiedName());
        endEvent.put("success", result.success());
        endEvent.put("latency_ms", result.latencyMs());
        endEvent.put("attempts", result.attempts());
        endEvent.put("error_type", result.errorType());
        endEvent.put("summary", result.summary(300));
        emit.accept(AgentEvent.of("tool_end", endEvent));

        TraceCollector collector = TraceContext.peek();
        if (collector != null) {
            collector.addSpan("工具 " + entry.qualifiedName(), "tool",
                    Map.of("args", args),
                    Map.of("success", result.success(), "summary", result.summary(300)),
                    result.success() ? "success" : "failed", result.errorMessage(), result.latencyMs());
        }
        return result;
    }

    // ------------------------------------------------------------------ Skill

    private void executeSkill(RoutePlan.RunSkill run, TurnContracts.Request request,
                              State state, Consumer<AgentEvent> emit) {
        Optional<SkillEntity> found = request.snapshot().skill(run.skill());
        if (found.isEmpty()) {
            String message = "Skill `%s` 不存在或未发布。".formatted(run.skill());
            emit.accept(AgentEvent.of("guard", "kind", "skill_not_found", "message", message));
            state.evidence.add("[路由错误] " + message);
            return;
        }
        SkillEntity skill = found.get();

        Map<String, Object> inputs = new LinkedHashMap<>(run.inputs());
        if (inputs.isEmpty()) {
            inputs.putAll(ruleEngine.extractArguments(request.question(),
                    CapabilityRegistry.requiredOf(skill.getInputSchema()),
                    CapabilityRegistry.propertiesOf(skill.getInputSchema())));
        }

        List<String> missing = AnswerVerifier.missingRequired(skill.getInputSchema(), inputs);
        if (!missing.isEmpty()) {
            String message = "执行 Skill `%s` 缺少参数：%s。请补充。".formatted(
                    skill.getDisplayName().isBlank() ? skill.getName() : skill.getDisplayName(),
                    String.join(", ", missing));
            emit.accept(AgentEvent.of("guard", "kind", "missing_arguments", "message", message));
            state.finalAnswer = message;
            return;
        }

        if ("high".equals(skill.getRiskLevel())
                && !passApproval("skill:" + skill.getName(), inputs, state, emit, request, run,
                        "skill", skill.getName(), null)) {
            return;
        }

        emit.accept(AgentEvent.of("skill_start",
                "skill", skill.getName(), "display_name", skill.getDisplayName(),
                "steps", skill.getSteps().size(), "inputs", inputs));

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("input", inputs);
        Map<String, Object> stepResults = new LinkedHashMap<>();
        scope.put("steps", stepResults);

        int failures = 0;
        int index = 0;
        for (Map<String, Object> step : skill.getSteps()) {
            index++;
            String stepId = step.get("id") != null ? String.valueOf(step.get("id")) : "step" + index;
            String type = step.get("type") != null ? String.valueOf(step.get("type")) : "tool";
            boolean optional = Boolean.TRUE.equals(step.get("optional"));

            if ("llm".equals(type)) {
                String prompt = SkillTemplates.render(String.valueOf(step.getOrDefault("prompt", "")), scope);
                LlmResult result = llmRouter.complete(
                        "你是企业业务流程助手，基于给定数据完成该步骤。", prompt);
                String text = result.text() == null || result.text().isBlank()
                        ? "（模型不可用，该步骤已跳过）" : result.text();
                stepResults.put(stepId, Map.of("success", true, "data", text));
                state.evidence.add("[%s/%s] 分析结论：%s".formatted(skill.getName(), stepId,
                        text.length() > 300 ? text.substring(0, 300) : text));
                continue;
            }

            Optional<ToolEntry> entry = request.snapshot().tool(String.valueOf(step.get("tool")));
            if (entry.isEmpty()) {
                failures++;
                emit.accept(AgentEvent.of("skill_step", "skill", skill.getName(), "step", stepId,
                        "success", false, "message", "步骤工具不可用：" + step.get("tool")));
                if (optional) continue;
                break;
            }

            Map<String, Object> stepArgs = SkillTemplates.resolveStepArgs(step, scope);
            if (guardBlocked(entry.get().qualifiedName(), stepArgs, state, emit)) break;

            ToolResult result = invokeTool(entry.get(), stepArgs, state, emit,
                    Map.of("skill", skill.getName(), "step", stepId));

            stepResults.put(stepId, Map.of("success", result.success(),
                    "data", result.data() == null ? Map.of() : result.data()));
            state.evidence.add("[%s/%s] `%s` → %s".formatted(skill.getName(), stepId,
                    entry.get().qualifiedName(), result.summary(400)));

            if (!result.success()) {
                failures++;
                if (!optional) {
                    emit.accept(AgentEvent.of("skill_step", "skill", skill.getName(), "step", stepId,
                            "success", false,
                            "message", "步骤失败，终止后续步骤：" + result.errorMessage()));
                    break;
                }
            }
        }

        emit.accept(AgentEvent.of("skill_end", "skill", skill.getName(), "failures", failures));

        if (!skill.getOutputTemplate().isBlank()) {
            String rendered = SkillTemplates.render(skill.getOutputTemplate(), scope);
            if (!rendered.isBlank()) {
                state.evidence.add("[%s] 汇总：%s".formatted(skill.getName(), rendered));
            }
        }
    }

    // ------------------------------------------------------------------ 子 Agent

    private void executeAgent(RoutePlan.Delegate delegate, TurnContracts.Request request,
                              State state, Consumer<AgentEvent> emit) {
        Optional<SubAgentEntity> found = request.snapshot().agent(delegate.agent());
        if (found.isEmpty()) {
            String message = "子 Agent `%s` 不存在或未发布。".formatted(delegate.agent());
            emit.accept(AgentEvent.of("guard", "kind", "agent_not_found", "message", message));
            state.evidence.add("[路由错误] " + message);
            return;
        }
        SubAgentEntity agent = found.get();

        // 远程任务的续跑：审批已放行，直接对既有 taskId 续发消息，不重新委派
        DelegationContracts.RemoteTask resumeTask = remoteResumeFor(request, agent);

        // 委派前的粗粒度审批：把任务交给平台管不到的外部服务，是需要留痕的动作。
        // 本地子 Agent 不走这一关 —— 它的每一次工具调用都已在平台侧逐个把关。
        if (resumeTask == null && requiresDelegationApproval(agent)
                && !passApproval("agent:" + agent.getName(),
                        Map.of("task", request.question()), state, emit, request, delegate,
                        "agent", agent.getName(), null)) {
            return;
        }

        DelegationContracts.Request delegation = new DelegationContracts.Request(
                agent, request.question(), request.identity(), request.snapshot(),
                request.sessionId(), request.traceId(), resumeTask);

        DelegationContracts.Result result = agentGateway.delegate(
                delegation, delegationHost(state, emit, request, delegate, agent), emit);

        state.evidence.addAll(result.evidence());

        // 宿主已在 State 上记录了挂起/拒绝（本地子 Agent 撞上工具审批）
        if (result.suspended()) return;

        // 远端主动要求人工输入：转成平台审批单，把 taskId 存进恢复快照
        if (result.needsInput()) {
            Map<String, Object> approval = createRemoteApproval(request, agent, result);
            state.pendingApproval = approval;
            state.status = "waiting_approval";
            emit.accept(AgentEvent.of("approval_required", approval));
            return;
        }

        // 失败不中断编排：证据已记下，交给下一轮路由重新决策或收敛为答复
        if (result.failed()) {
            emit.accept(AgentEvent.of("guard", "kind", "agent_failed",
                    "message", "子 Agent %s 执行失败（%s）：%s".formatted(
                            agent.getName(), result.errorType(), result.errorMessage())));
        }
    }

    /**
     * 传输层回调宿主。
     *
     * <p>护栏、审批、工具调用三道关卡留在运行时，传输层只能通过这个接口触达 ——
     * 本地子 Agent 因此无法绕开平台的把关自行发起调用。
     */
    private DelegationContracts.Host delegationHost(State state, Consumer<AgentEvent> emit,
                                                    TurnContracts.Request request,
                                                    RoutePlan plan, SubAgentEntity agent) {
        return new DelegationContracts.Host() {
            @Override
            public boolean degraded() {
                return state.degraded;
            }

            @Override
            public void markDegraded() {
                state.degraded = true;
            }

            @Override
            public boolean guardBlocked(String qualifiedName, Map<String, Object> args) {
                return AgentRuntime.this.guardBlocked(qualifiedName, args, state, emit);
            }

            @Override
            public DelegationContracts.Approval approval(ToolEntry entry, Map<String, Object> args) {
                if (!requiresApproval(entry)) return DelegationContracts.Approval.NOT_REQUIRED;
                boolean allowed = passApproval(entry.qualifiedName(), args, state, emit,
                        request, plan, "agent", agent.getName(), entry);
                if (allowed) return DelegationContracts.Approval.APPROVED;
                return "waiting_approval".equals(state.status)
                        ? DelegationContracts.Approval.PENDING
                        : DelegationContracts.Approval.REJECTED;
            }

            @Override
            public ToolResult invokeTool(ToolEntry entry, Map<String, Object> args,
                                         Map<String, Object> extraEventFields) {
                return AgentRuntime.this.invokeTool(entry, args, state, emit, extraEventFields);
            }
        };
    }

    /**
     * 是否需要委派前审批。
     *
     * <p>只对远程 Agent 生效，且放行 low 风险 —— 否则每次跨部门查询都要人点一次，
     * 审批会退化成橡皮图章，反而稀释了真正高风险动作的信号。
     */
    private boolean requiresDelegationApproval(SubAgentEntity agent) {
        return agent.isRemote()
                && a2aConfig.requireDelegationApproval()
                && !"low".equals(agent.getRiskLevel());
    }

    /** 从恢复快照里取出远程任务坐标；不是同一个 Agent 的快照一律忽略。 */
    private DelegationContracts.RemoteTask remoteResumeFor(TurnContracts.Request request,
                                                           SubAgentEntity agent) {
        if (!request.isResume()) return null;
        TurnContracts.Resume.RemoteTask task = request.resume().remoteTask();
        if (task == null || !agent.getName().equals(task.agentName())) return null;

        String comment = request.resume().comment();
        String reply = request.resume().approved()
                ? (comment == null || comment.isBlank() ? "已批准，请继续执行。" : comment)
                : "该请求已被拒绝，请勿执行该操作，并说明还缺少什么。";
        return new DelegationContracts.RemoteTask(task.taskId(), task.contextId(), reply);
    }

    /**
     * 远端要求人工输入 → 平台审批单。
     *
     * <p>与工具审批的关键差异：恢复快照里存的不是「重放哪个决策」，而是
     * 「续发到哪个远程任务」—— A2A 的任务在远端仍然活着，等着我们回话。
     */
    private Map<String, Object> createRemoteApproval(TurnContracts.Request request,
                                                     SubAgentEntity agent,
                                                     DelegationContracts.Result result) {
        String question = result.prompt();

        ApprovalEntity approval = new ApprovalEntity();
        approval.setSessionId(request.sessionId());
        approval.setTraceId(request.traceId());
        approval.setRequestedBy(request.identity().id());
        approval.setExecutorType("agent");
        approval.setExecutorName(agent.getName());
        approval.setToolName("a2a:" + agent.getName());
        approval.setToolArgs(Map.of("task", request.question()));
        approval.setRiskLevel("high");
        approval.setRiskReason("远程子 Agent 请求人工确认：" + question);
        approval.setImpactScope("远端系统：%s（平台无法枚举其内部动作）".formatted(agent.getEndpoint()));

        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("route_type", "agent");
        resume.put("target", agent.getName());
        resume.put("arguments", Map.of());
        resume.put("confidence", 1.0);
        resume.put("reason", "远程任务等待人工输入");
        resume.put("signature", "");
        resume.put("question", request.question());
        resume.put("a2a_agent", agent.getName());
        resume.put("a2a_task_id", result.remoteTaskId());
        resume.put("a2a_context_id", result.remoteContextId());
        approval.setResumePayload(resume);
        approvalRepository.save(approval);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", approval.getId());
        payload.put("executor_type", "agent");
        payload.put("executor_name", agent.getName());
        payload.put("tool_name", "a2a:" + agent.getName());
        payload.put("tool_args", Map.of("task", request.question()));
        payload.put("risk_level", "high");
        payload.put("risk_reason", approval.getRiskReason());
        payload.put("impact_scope", approval.getImpactScope());
        payload.put("signature", "");
        payload.put("remote_prompt", question);
        payload.put("remote_task_id", result.remoteTaskId());
        return payload;
    }

    // ------------------------------------------------------------------ 护栏与审批

    private boolean guardBlocked(String tool, Map<String, Object> args, State state,
                                 Consumer<AgentEvent> emit) {
        LoopGuard.Verdict verdict = state.guard.check(tool, args);
        if (!verdict.blocked()) return false;

        emit.accept(AgentEvent.of("guard", "kind", verdict.kind(), "message", verdict.message()));
        state.finalAnswer = verdict.message() + "\n\n" + verdict.hint();
        state.status = "blocked";

        TraceCollector collector = TraceContext.peek();
        if (collector != null) {
            collector.addSpan("循环护栏拦截", "guard", Map.of("tool", tool, "args", args),
                    Map.of("kind", verdict.kind()), "blocked", verdict.message(), 0);
        }
        return true;
    }

    private boolean requiresApproval(ToolEntry entry) {
        return "high".equals(entry.tool().getRiskLevel()) || !entry.tool().isReadOnly();
    }

    /**
     * Human-in-the-Loop 关卡。
     *
     * @return true 表示可以继续执行；false 表示已挂起等待审批或被拒绝
     */
    private boolean passApproval(String signatureKey, Map<String, Object> args, State state,
                                 Consumer<AgentEvent> emit, TurnContracts.Request request,
                                 RoutePlan plan, String executorType, String executorName,
                                 ToolEntry entry) {
        String signature = LoopGuard.signature(signatureKey, args);
        String decision = state.approvals.get(signature);

        if (decision == null) {
            Map<String, Object> approval = createApproval(request, plan, executorType, executorName,
                    entry, args, signature);
            state.pendingApproval = approval;
            state.status = "waiting_approval";
            emit.accept(AgentEvent.of("approval_required", approval));

            TraceCollector collector = TraceContext.peek();
            if (collector != null) {
                collector.addSpan("人工审批中断", "approval",
                        Map.of("tool", approval.get("tool_name"), "args", args),
                        Map.of("approval_id", approval.get("id")), "pending", "", 0);
            }
            return false;
        }

        if ("rejected".equals(decision)) {
            String message = "操作 `%s` 已被拒绝。我不会执行该写操作。".formatted(signatureKey)
                    + "你可以修改参数后重新发起，或改用只读方式查询。";
            emit.accept(AgentEvent.of("guard", "kind", "approval_rejected", "message", message));
            state.finalAnswer = message;
            return false;
        }
        return true;
    }

    private Map<String, Object> createApproval(TurnContracts.Request request, RoutePlan plan,
                                               String executorType, String executorName,
                                               ToolEntry entry, Map<String, Object> args,
                                               String signature) {
        String toolName = entry != null ? entry.qualifiedName() : executorName;

        // 三种关卡的影响面差别很大，审批人看到的必须是当前这一种，
        // 否则「委派给外部 Agent」会被描述成「写操作」，评估依据就错了
        String reason;
        String impact;
        if (entry != null) {
            reason = "`%s` 为写操作/高风险工具，执行后将变更下游业务系统数据。".formatted(toolName);
            impact = "影响系统：%s；作用对象：%s".formatted(
                    entry.server().getDisplayName().isBlank()
                            ? entry.server().getName() : entry.server().getDisplayName(),
                    args.isEmpty() ? "无参数" : describeArgs(args));
        } else if ("agent".equals(executorType)) {
            reason = "即将把整个任务委派给子 Agent `%s` 执行。".formatted(executorName);
            impact = "任务将整体移交给该 Agent。若为远程 Agent，其内部调用发生在平台之外，"
                    + "平台无法逐个枚举或拦截 —— 放行等于信任该 Agent 在其声明范围内的全部动作。";
        } else {
            reason = "Skill `%s` 被标记为高风险。".formatted(executorName);
            impact = "将按 Skill 定义依次调用多个下游系统，涉及写操作";
        }

        ApprovalEntity approval = new ApprovalEntity();
        approval.setSessionId(request.sessionId());
        approval.setTraceId(request.traceId());
        approval.setRequestedBy(request.identity().id());
        approval.setExecutorType(executorType);
        approval.setExecutorName(executorName);
        approval.setToolName(toolName);
        approval.setToolArgs(args);
        approval.setRiskLevel("high");
        approval.setRiskReason(reason);
        approval.setImpactScope(impact);
        approval.setResumePayload(Map.of(
                "route_type", plan.type(),
                "target", plan.target(),
                "arguments", args,
                "confidence", plan.confidence(),
                "reason", plan.reason(),
                "signature", signature,
                "question", request.question()));
        approvalRepository.save(approval);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", approval.getId());
        payload.put("executor_type", executorType);
        payload.put("executor_name", executorName);
        payload.put("tool_name", toolName);
        payload.put("tool_args", args);
        payload.put("risk_level", "high");
        payload.put("risk_reason", reason);
        payload.put("impact_scope", impact);
        payload.put("signature", signature);
        return payload;
    }

    private String describeArgs(Map<String, Object> args) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (count++ >= 4) break;
            if (count > 1) sb.append(", ");
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 收敛

    private String finalize(RoutePlan plan, State state, TurnContracts.Request request,
                            Consumer<AgentEvent> emit, TraceCollector collector) {
        if (state.finalAnswer != null) {
            emit.accept(AgentEvent.of("answer", "text", state.finalAnswer, "streamed", false));
            return state.finalAnswer;
        }

        if (plan instanceof RoutePlan.Reply reply && state.evidence.isEmpty()) {
            emit.accept(AgentEvent.of("answer", "text", reply.message(), "streamed", false));
            return reply.message();
        }
        if (plan instanceof RoutePlan.Clarify clarify) {
            emit.accept(AgentEvent.of("answer", "text", clarify.message(), "streamed", false));
            return clarify.message();
        }

        emit.accept(AgentEvent.of("status", "phase", "summarizing", "message", "正在汇总执行结果…"));

        StringBuilder prompt = new StringBuilder("用户请求：").append(request.question())
                .append("\n\n工具执行证据（只能使用这些事实）：\n");
        if (state.evidence.isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (int i = 0; i < state.evidence.size(); i++) {
                prompt.append(i + 1).append(". ").append(state.evidence.get(i)).append('\n');
            }
        }
        prompt.append("\n请用中文给出最终答复。");

        LlmResult result = llmRouter.complete(Prompts.ANSWER_IDENTITY, prompt.toString());
        collector.addTokens(result.promptTokens(), result.completionTokens());

        String answer = result.text() == null || result.text().isBlank()
                ? fallbackAnswer(state) : result.text().trim();

        // 防幻觉：未真正完成写操作却声称已完成 → 纠正
        AnswerVerifier.Verdict verdict = AnswerVerifier.verify(answer, state.hadSuccessfulWrite,
                state.evidence);
        if (!verdict.ok()) {
            emit.accept(AgentEvent.of("guard", "kind", verdict.reason(),
                    "message", "检测到未经证据支撑的完成声明，已纠正答复。"));
            answer = verdict.corrected();
            emit.accept(AgentEvent.of("answer", "text", answer, "streamed", false, "corrected", true));
        } else {
            emit.accept(AgentEvent.of("answer", "text", answer, "streamed", false));
        }

        collector.addSpan("结果汇总", "llm",
                Map.of("evidence_count", state.evidence.size()),
                Map.of("answer_chars", answer.length(), "guard_corrected", !verdict.ok()),
                "success", "", 0);
        return answer;
    }

    /** LLM 不可用时的确定性答复：直接结构化呈现工具证据。 */
    private String fallbackAnswer(State state) {
        if (state.evidence.isEmpty()) {
            return "当前没有可用的执行结果。请补充更具体的信息（例如工号 E1001、工单号 INC-xxx、"
                    + "客户编号 C-xxxx），我再为你查询。";
        }
        StringBuilder sb = new StringBuilder("本轮的工具调用结果如下：\n\n");
        for (int i = 0; i < state.evidence.size(); i++) {
            sb.append(i + 1).append(". ").append(state.evidence.get(i)).append('\n');
        }
        sb.append("\n> 当前未配置模型 API Key，以上为工具原始返回的结构化摘要（规则引擎模式）。");
        return sb.toString();
    }

    private TurnContracts.Result finish(State state, RoutePlan plan,
                                        ContextAssembler.Assembled context,
                                        RouteCandidates candidates, TurnContracts.Request request,
                                        TraceCollector collector) {
        String status = switch (state.status) {
            case "waiting_approval", "blocked", "failed" -> state.status;
            default -> "success";
        };

        return new TurnContracts.Result(
                status,
                state.finalAnswer == null ? "" : state.finalAnswer,
                plan,
                List.copyOf(state.evidence),
                state.iterations,
                state.toolCalls,
                state.toolFailures,
                state.pendingApproval,
                context == null ? request.summary() : context.summary(),
                context == null ? 0 : context.tokens(),
                context != null && context.compressed(),
                candidates == null ? 0 : candidates.visibleCount(),
                request.snapshot().totalToolCount(),
                collector.elapsedMs(),
                collector.promptTokens(),
                collector.completionTokens(),
                collector.modelUsed().isBlank()
                        ? (state.degraded ? "rule-engine" : "") : collector.modelUsed(),
                collector.modelFallback(),
                collector.degradedToRules() || state.degraded,
                state.guard.snapshot(),
                collector.spans());
    }

    private void recordToolMetrics(McpToolEntity tool, ToolResult result) {
        tool.setCallCount(tool.getCallCount() + 1);
        if (result.success()) tool.setSuccessCount(tool.getSuccessCount() + 1);
        tool.setTotalLatencyMs(tool.getTotalLatencyMs() + result.latencyMs());
        toolRepository.save(tool);
    }
}
