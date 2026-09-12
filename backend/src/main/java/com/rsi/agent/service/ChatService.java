package com.rsi.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.rsi.agent.agent.AgentEvent;
import com.rsi.agent.agent.AgentRuntime;
import com.rsi.agent.agent.CapabilityRegistry;
import com.rsi.agent.agent.CapabilitySnapshot;
import com.rsi.agent.agent.ContextAssembler;
import com.rsi.agent.agent.RoutePlan;
import com.rsi.agent.agent.TurnContracts;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.ApprovalEntity;
import com.rsi.agent.domain.ChatSessionEntity;
import com.rsi.agent.domain.MessageEntity;
import com.rsi.agent.dto.PlatformDtos.MessageDto;
import com.rsi.agent.dto.PlatformDtos.SessionDto;
import com.rsi.agent.observability.TraceContext;
import com.rsi.agent.repository.ChatSessionRepository;
import com.rsi.agent.repository.MessageRepository;
import com.rsi.agent.security.Identity;
import com.rsi.agent.security.IdentityContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/** 对话服务：会话管理 + SSE 流式编排 + 落库。 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int HISTORY_TURNS = 20;

    private final ObjectMapper objectMapper;
    private final ChatSessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final CapabilityRegistry capabilityRegistry;
    private final AgentRuntime runtime;
    private final TraceService traceService;
    private final ApprovalService approvalService;

    /** 虚拟线程：SSE 连接期间线程被长时间占用，虚拟线程让并发会话不受平台线程数限制。 */
    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-", 0).factory());

    public ChatService(ObjectMapper objectMapper, ChatSessionRepository sessionRepository,
                       MessageRepository messageRepository, CapabilityRegistry capabilityRegistry,
                       AgentRuntime runtime, TraceService traceService,
                       ApprovalService approvalService) {
        this.objectMapper = objectMapper;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.capabilityRegistry = capabilityRegistry;
        this.runtime = runtime;
        this.traceService = traceService;
        this.approvalService = approvalService;
    }

    // ---------------------------------------------------------------- 会话管理

    public List<SessionDto> listSessions(Identity identity) {
        return sessionRepository
                .findByUserIdOrderByUpdatedAtDesc(identity.id(), PageRequest.of(0, 100))
                .stream().map(SessionDto::from).toList();
    }

    @Transactional
    public SessionDto createSession(String title, Identity identity) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTitle(title == null || title.isBlank() ? "新会话" : title);
        session.setUserId(identity.id());
        session.setTenantId(identity.tenantId());
        sessionRepository.save(session);
        return SessionDto.from(session);
    }

    @Transactional
    public void deleteSession(String sessionId, Identity identity) {
        ChatSessionEntity session = requireSession(sessionId, identity);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
    }

    public List<MessageDto> messages(String sessionId, Identity identity) {
        requireSession(sessionId, identity);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream().map(MessageDto::from).toList();
    }

    // ---------------------------------------------------------------- SSE 编排

    /** 发送消息，流式返回编排全过程。 */
    public ResponseBodyEmitter stream(String sessionId, String message, Identity identity) {
        if (message == null || message.isBlank()) {
            throw BizException.badRequest("消息内容不能为空");
        }
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(300_000L);
        executor.execute(() -> runTurn(emitter, sessionId, message, identity, null));
        return emitter;
    }

    /** 审批放行后恢复执行 —— 从执行节点续跑，不重新路由。 */
    public ResponseBodyEmitter resume(String approvalId, Identity identity) {
        ApprovalEntity approval = approvalService.require(approvalId);
        if ("pending".equals(approval.getStatus())) {
            throw BizException.badRequest("该审批尚未完成，无法恢复执行");
        }

        Map<String, Object> payload = approval.getResumePayload();
        RoutePlan plan = rebuildPlan(payload);
        TurnContracts.Resume resume = new TurnContracts.Resume(plan,
                String.valueOf(payload.getOrDefault("signature", "")),
                approval.getStatus(), approval.getDecisionComment(),
                rebuildRemoteTask(payload));

        String question = String.valueOf(payload.getOrDefault("question", ""));
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(300_000L);
        executor.execute(() ->
                runTurn(emitter, approval.getSessionId(), question, identity, resume));
        return emitter;
    }

    /**
     * 从审批快照重建远端任务坐标。
     *
     * <p>只有远程子 Agent 主动要求人工输入时才会写这几个字段。恢复时凭它们
     * 对那个仍在 {@code input-required} 状态的远端任务续发消息，而不是重新委派 ——
     * 重新委派会让远端起一个新任务，旧任务悬空。
     */
    private TurnContracts.Resume.RemoteTask rebuildRemoteTask(Map<String, Object> payload) {
        Object agentName = payload.get("a2a_agent");
        if (agentName == null || String.valueOf(agentName).isBlank()) return null;
        return new TurnContracts.Resume.RemoteTask(
                String.valueOf(agentName),
                String.valueOf(payload.getOrDefault("a2a_task_id", "")),
                String.valueOf(payload.getOrDefault("a2a_context_id", "")));
    }

    /** 从审批快照重建执行路径。审批单里存的是决策本身，恢复时无需再问一次模型。 */
    @SuppressWarnings("unchecked")
    private RoutePlan rebuildPlan(Map<String, Object> payload) {
        String type = String.valueOf(payload.getOrDefault("route_type", "tool"));
        String target = String.valueOf(payload.getOrDefault("target", ""));
        double confidence = payload.get("confidence") instanceof Number n ? n.doubleValue() : 0;
        String reason = String.valueOf(payload.getOrDefault("reason", ""));
        Map<String, Object> args = payload.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        return switch (type) {
            case "skill" -> new RoutePlan.RunSkill(target, args, confidence, reason);
            case "agent" -> new RoutePlan.Delegate(target, confidence, reason);
            default -> new RoutePlan.CallTool(target, args, confidence, reason);
        };
    }

    /**
     * 一次完整的 turn。
     *
     * <p>跑在虚拟线程上，因此必须<b>手动重建 ThreadLocal 身份上下文</b> ——
     * 否则服务层的 {@code @RequiresScope} 切面会因取不到身份而拒绝执行。
     * 这是 ThreadLocal + 异步执行组合时最容易踩的坑。
     */
    private void runTurn(ResponseBodyEmitter emitter, String sessionId, String question,
                         Identity identity, TurnContracts.Resume resume) {
        IdentityContext.set(identity);
        try {
            ChatSessionEntity session = resolveSession(sessionId, question, identity);
            send(emitter, "session", Map.of("session_id", session.getId(), "title", session.getTitle()));

            if (resume == null) {
                MessageEntity userMessage = new MessageEntity();
                userMessage.setSessionId(session.getId());
                userMessage.setRole("user");
                userMessage.setContent(question);
                messageRepository.save(userMessage);
            }

            List<ContextAssembler.Turn> history = loadHistory(session.getId());
            CapabilitySnapshot snapshot = capabilityRegistry.load(identity);
            String traceId = UUID.randomUUID().toString().replace("-", "");

            TurnContracts.Request request = new TurnContracts.Request(
                    session.getId(), question, identity, snapshot, history,
                    session.getSummary(), traceId, resume);

            TurnContracts.Result result = runtime.run(request,
                    event -> send(emitter, event.event(), event.data()));

            persistTurn(session, identity, question, traceId, result);
            send(emitter, "done", result.toPayload());
            emitter.complete();

        } catch (Exception e) {
            log.error("对话流执行失败", e);
            send(emitter, "error", Map.of("message", e.getClass().getSimpleName() + ": " + e.getMessage()));
            send(emitter, "done", Map.of("status", "failed", "answer", "",
                    "error", String.valueOf(e.getMessage())));
            emitter.complete();
        } finally {
            IdentityContext.clear();
            TraceContext.clear();
        }
    }

    /**
     * 手写 SSE 帧。
     *
     * <p>不用 {@code SseEmitter.event()} 是因为它输出的是 {@code data:{...}}（冒号后无空格）。
     * SSE 规范允许省略这个空格，但既有前端按 {@code "data: "} 前缀解析 —— 前端要求保持不变，
     * 所以由后端产出标准带空格的帧，用 ResponseBodyEmitter 直接写原始文本。
     */
    private void send(ResponseBodyEmitter emitter, String event, Map<String, Object> data) {
        try {
            String payload = objectMapper.writeValueAsString(data == null ? Map.of() : data);
            emitter.send("event: " + event + "\n" + "data: " + payload + "\n\n", MediaType.TEXT_PLAIN);
        } catch (Exception e) {
            // 客户端断开是常态（用户关页面），不必当作错误处理
            log.debug("SSE 推送失败（客户端可能已断开）: {}", e.toString());
        }
    }

    @Transactional
    protected ChatSessionEntity resolveSession(String sessionId, String question, Identity identity) {
        if (sessionId != null && !sessionId.isBlank()) {
            return requireSession(sessionId, identity);
        }
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTitle(question.length() > 24 ? question.substring(0, 24) + "…" : question);
        session.setUserId(identity.id());
        session.setTenantId(identity.tenantId());
        sessionRepository.save(session);
        return session;
    }

    private List<ContextAssembler.Turn> loadHistory(String sessionId) {
        List<MessageEntity> rows = messageRepository.findBySessionIdAndRoleInOrderByCreatedAtDesc(
                sessionId, List.of("user", "assistant"), PageRequest.of(0, HISTORY_TURNS));

        List<ContextAssembler.Turn> turns = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            turns.add(new ContextAssembler.Turn(rows.get(i).getRole(), rows.get(i).getContent()));
        }
        return turns;
    }

    @Transactional
    protected void persistTurn(ChatSessionEntity session, Identity identity, String question,
                               String traceId, TurnContracts.Result result) {
        if (result.answer() != null && !result.answer().isBlank()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("trace_id", traceId);
            meta.put("tool_calls", result.toolCalls());
            meta.put("latency_ms", result.latencyMs());
            meta.put("degraded", result.degraded());
            if (result.plan() != null) {
                meta.put("route", Map.of("route_type", result.plan().type(),
                        "target", result.plan().target()));
            }

            MessageEntity assistant = new MessageEntity();
            assistant.setSessionId(session.getId());
            assistant.setRole("assistant");
            assistant.setContent(result.answer());
            assistant.setMeta(meta);
            messageRepository.save(assistant);
        }

        session.setTurnCount(session.getTurnCount() + 1);
        session.setTotalTokens(session.getTotalTokens()
                + result.promptTokens() + result.completionTokens());
        if (result.summary() != null && !result.summary().isBlank()) {
            session.setSummary(result.summary());
        }
        session.setStatus("waiting_approval".equals(result.status()) ? "waiting_approval" : "active");
        sessionRepository.save(session);

        traceService.persist(traceId, session.getId(), session.getTurnCount(), identity,
                question, result);
    }

    private ChatSessionEntity requireSession(String sessionId, Identity identity) {
        ChatSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> BizException.notFound("会话"));
        if (!session.getUserId().equals(identity.id()) && !"admin".equals(identity.role())) {
            throw BizException.forbidden("无权访问该会话");
        }
        return session;
    }
}
