package com.rsi.agent.agent.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsi.agent.agent.AgentEvent;
import com.rsi.agent.agent.a2a.A2aProtocol;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.mcp.CircuitBreaker;
import com.rsi.agent.mcp.CircuitOpenException;
import com.rsi.agent.observability.TraceCollector;
import com.rsi.agent.observability.TraceContext;
import com.rsi.agent.security.Identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A2A 传输：子 Agent 是外部部门自己部署的服务。
 *
 * <p>卡片声明 {@code capabilities.streaming=true} 时走 {@code message/stream}，
 * 逐帧把远端进度转成平台 SSE 事件；否则退回 {@code message/send} 并在必要时
 * 轮询 {@code tasks/get} —— 协商方式由 Agent Card 决定，不需要注册方额外配置。
 *
 * <h3>与本地子 Agent 的三点本质差异</h3>
 * <ol>
 *   <li><b>工具白名单不再是强制边界</b>。远端用自己的工具，平台看不见也拦不住，
 *       {@code toolAllowlist} 在这里退化为申报信息，只用于审计比对。</li>
 *   <li><b>不重试</b>。工具调用多为只读，重试是安全的；Agent 委派大概率有副作用，
 *       重试等于重复执行写操作。只有熔断与超时，没有 {@code @Resilient}。</li>
 *   <li><b>Trace 是黑盒</b>。远端内部的 span 拿不到，只记一个粗粒度委派 span。
 *       带 W3C {@code traceparent} 是尽力而为 —— A2A 并未规定它。</li>
 * </ol>
 */
@Component
public class A2aAgentTransport implements AgentTransport {

    private static final Logger log = LoggerFactory.getLogger(A2aAgentTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final CircuitBreaker circuitBreaker;
    private final AgentProperties.A2a config;
    private final AtomicInteger requestId = new AtomicInteger(1);

    public A2aAgentTransport(CircuitBreaker circuitBreaker, AgentProperties properties) {
        this.circuitBreaker = circuitBreaker;
        this.config = properties.a2a();

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(config.connectTimeoutSeconds()).toMillis());
        // 读超时按单帧算：流式场景下远端可能长时间静默但任务仍在推进
        factory.setReadTimeout((int) Duration.ofSeconds(config.readTimeoutSeconds()).toMillis());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String transport() { return "a2a"; }

    @Override
    public DelegationContracts.Result delegate(DelegationContracts.Request request,
                                               DelegationContracts.Host host,
                                               Consumer<AgentEvent> emit) {
        long started = System.nanoTime();
        SubAgentEntity agent = request.agent();
        String key = "agent:" + agent.getName();

        if (agent.getEndpoint() == null || agent.getEndpoint().isBlank()) {
            return DelegationContracts.Result.failed("misconfigured",
                    "远程子 Agent 未配置 endpoint，请重新拉取 Agent Card。",
                    List.of("[%s] 未配置 endpoint，委派未发出。".formatted(agent.getName())),
                    0, elapsed(started));
        }

        if (!circuitBreaker.allow(key)) {
            String message = "远程子 Agent %s 熔断中，暂不派发。".formatted(agent.getName());
            emit.accept(AgentEvent.of("guard", "kind", "agent_circuit_open", "message", message));
            return DelegationContracts.Result.failed("circuit_open", message,
                    List.of("[%s] %s".formatted(agent.getName(), message)), 0, elapsed(started));
        }

        emit.accept(AgentEvent.of("agent_start",
                "agent", agent.getName(), "display_name", agent.getDisplayName(),
                "transport", "a2a", "endpoint", agent.getEndpoint(),
                "streaming", agent.isStreaming(),
                "resumed", request.isResume(),
                "budget", agent.getMaxIterations()));

        List<String> evidence = new ArrayList<>();
        try {
            Map<String, Object> message = buildMessage(request);
            DelegationContracts.Result result = agent.isStreaming()
                    ? runStreaming(request, message, evidence, emit, started)
                    : runBlocking(request, message, evidence, emit, started);

            circuitBreaker.record(key, !result.failed());
            recordSpan(agent, request, result);
            emit.accept(AgentEvent.of("agent_end", "agent", agent.getName(),
                    "transport", "a2a", "status", result.status(),
                    "task_id", result.remoteTaskId(), "steps", result.steps()));
            return result;

        } catch (Exception e) {
            circuitBreaker.record(key, false);
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("A2A 委派失败 {} -> {}: {}", agent.getName(), agent.getEndpoint(), detail);

            evidence.add("[%s] 远程调用失败：%s".formatted(agent.getName(), detail));
            emit.accept(AgentEvent.of("agent_end", "agent", agent.getName(),
                    "transport", "a2a", "status", "failed", "error", detail));
            return DelegationContracts.Result.failed(classify(e), detail, evidence,
                    evidence.size(), elapsed(started));
        }
    }

    // ------------------------------------------------------------------ 报文

    /**
     * 构造 A2A 消息。
     *
     * <p>用户身份放在 {@code metadata} 里，并显式标注 {@code unsigned} ——
     * 当前注册模型只配了服务间静态凭证，这段身份<b>没有签名、不可验证</b>，
     * 只供远端记审计日志。远端若拿它做鉴权判断，是误用。
     */
    private Map<String, Object> buildMessage(DelegationContracts.Request request) {
        Identity identity = request.identity();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", identity.id());
        user.put("username", identity.username());
        user.put("tenant_id", identity.tenantId());
        user.put("role", identity.role());
        user.put("scopes", identity.sortedScopes());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rsi.platform", "rsi-agent-platform");
        metadata.put("rsi.session_id", request.sessionId());
        metadata.put("rsi.trace_id", request.traceId());
        metadata.put("rsi.user", user);
        metadata.put("rsi.identity_assertion", "unsigned; audit-only; not an authorization grant");

        if (request.isResume()) {
            DelegationContracts.RemoteTask resume = request.resume();
            String reply = resume.reply() == null || resume.reply().isBlank()
                    ? "已批准，请继续执行。" : resume.reply();
            return A2aProtocol.userMessage(reply, resume.taskId(), resume.contextId(), metadata);
        }
        return A2aProtocol.userMessage(request.task(), null, null, metadata);
    }

    // ------------------------------------------------------------------ 流式

    private DelegationContracts.Result runStreaming(DelegationContracts.Request request,
                                                    Map<String, Object> message,
                                                    List<String> evidence,
                                                    Consumer<AgentEvent> emit,
                                                    long started) {
        SubAgentEntity agent = request.agent();
        Map<String, Object> payload = A2aProtocol.streamRequest(
                String.valueOf(requestId.getAndIncrement()), message);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.taskTimeoutSeconds());

        // 数组用于在 lambda 内回写 —— exchange 的函数式签名不允许捕获可变局部变量
        String[] ids = {"", ""};
        String[] finalState = {A2aProtocol.WORKING};
        String[] prompt = {""};

        client.post()
                .uri(agent.getEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .headers(headers -> requestHeaders(agent, request).forEach(headers::set))
                .body(payload)
                .exchange((httpRequest, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException(
                                "远端返回 HTTP " + response.getStatusCode().value());
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {

                        StringBuilder data = new StringBuilder();
                        // 标记已消费完毕，避免收尾时把最后一帧重复消费一次
                        boolean done = false;
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (System.nanoTime() > deadline) {
                                finalState[0] = A2aProtocol.FAILED;
                                evidence.add("[%s] 远程任务超时（>%ds），已断开。"
                                        .formatted(agent.getName(), config.taskTimeoutSeconds()));
                                done = true;
                                break;
                            }
                            if (line.isEmpty()) {
                                if (!data.isEmpty()
                                        && consume(data.toString(), agent, evidence, emit,
                                                   ids, finalState, prompt)) {
                                    done = true;
                                    break;
                                }
                                data.setLength(0);
                                continue;
                            }
                            if (line.startsWith("data:")) {
                                if (!data.isEmpty()) data.append('\n');
                                data.append(line.substring(5).stripLeading());
                            }
                            // event: / id: / retry: / 注释行一律忽略
                        }
                        // 流在最后一帧后没有空行就结束时，补消费一次
                        if (!done && !data.isEmpty()) {
                            consume(data.toString(), agent, evidence, emit, ids, finalState, prompt);
                        }
                    }
                    return null;
                });

        return toResult(agent, evidence, finalState[0], ids[0], ids[1], prompt[0], started);
    }

    /**
     * 消费一帧 SSE 数据。
     *
     * @return true 表示已到终态或需人工介入，调用方应停止读取
     */
    private boolean consume(String json, SubAgentEntity agent, List<String> evidence,
                            Consumer<AgentEvent> emit, String[] ids, String[] finalState,
                            String[] prompt) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            log.debug("忽略无法解析的 A2A 帧: {}", json);
            return false;
        }

        if (root.has("error")) {
            finalState[0] = A2aProtocol.FAILED;
            evidence.add("[%s] 远端返回错误：%s".formatted(agent.getName(),
                    root.path("error").path("message").asText(root.path("error").toString())));
            return true;
        }

        A2aProtocol.Event event = A2aProtocol.parseEvent(root.path("result"));
        if (!event.taskId().isBlank()) ids[0] = event.taskId();
        if (!event.contextId().isBlank()) ids[1] = event.contextId();

        if ("artifact-update".equals(event.kind())) {
            if (!event.text().isBlank()) {
                String label = event.artifactName().isBlank() ? "产出" : event.artifactName();
                evidence.add("[%s] %s：%s".formatted(agent.getName(), label, truncate(event.text())));
                emit.accept(AgentEvent.of("agent_progress", "agent", agent.getName(),
                        "kind", "artifact", "name", label, "text", truncate(event.text())));
            }
            return false;
        }

        String state = event.state();
        if (!state.isBlank()) finalState[0] = state;

        if (!event.text().isBlank()) {
            emit.accept(AgentEvent.of("agent_progress", "agent", agent.getName(),
                    "kind", "status", "state", state, "text", truncate(event.text())));
        }

        if (A2aProtocol.needsInput(state)) {
            prompt[0] = event.text();
            return true;
        }
        if (event.terminal()) {
            if (!event.text().isBlank()) {
                evidence.add("[%s] 结论：%s".formatted(agent.getName(), truncate(event.text())));
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 非流式

    private DelegationContracts.Result runBlocking(DelegationContracts.Request request,
                                                   Map<String, Object> message,
                                                   List<String> evidence,
                                                   Consumer<AgentEvent> emit,
                                                   long started) throws Exception {
        SubAgentEntity agent = request.agent();
        JsonNode result = rpc(agent, request,
                A2aProtocol.sendRequest(String.valueOf(requestId.getAndIncrement()), message));
        A2aProtocol.TaskSnapshot task = A2aProtocol.parseTask(result);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.taskTimeoutSeconds());
        // blocking=true 并非所有实现都遵守，非终态时补一轮轮询
        while (!A2aProtocol.isTerminal(task.state())
                && !A2aProtocol.needsInput(task.state())
                && !task.taskId().isBlank()
                && System.nanoTime() < deadline) {

            emit.accept(AgentEvent.of("agent_progress", "agent", agent.getName(),
                    "kind", "polling", "state", task.state(), "task_id", task.taskId()));
            Thread.sleep(config.pollIntervalMillis());

            JsonNode polled = rpc(agent, request, A2aProtocol.getTaskRequest(
                    String.valueOf(requestId.getAndIncrement()), task.taskId()));
            task = A2aProtocol.parseTask(polled);
        }

        for (String artifact : task.artifacts()) {
            evidence.add("[%s] %s".formatted(agent.getName(), truncate(artifact)));
        }
        if (!task.statusText().isBlank() && !A2aProtocol.needsInput(task.state())) {
            evidence.add("[%s] 结论：%s".formatted(agent.getName(), truncate(task.statusText())));
        }

        String state = A2aProtocol.isTerminal(task.state()) || A2aProtocol.needsInput(task.state())
                ? task.state() : A2aProtocol.FAILED;
        if (A2aProtocol.FAILED.equals(state) && task.state() != null && !task.state().isBlank()
                && !A2aProtocol.isTerminal(task.state())) {
            evidence.add("[%s] 远程任务超时（>%ds），停留在 %s。"
                    .formatted(agent.getName(), config.taskTimeoutSeconds(), task.state()));
        }

        return toResult(agent, evidence, state, task.taskId(), task.contextId(),
                task.statusText(), started);
    }

    private JsonNode rpc(SubAgentEntity agent, DelegationContracts.Request request,
                         Map<String, Object> payload) throws Exception {
        String body = client.post()
                .uri(agent.getEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> requestHeaders(agent, request).forEach(headers::set))
                .body(payload)
                .retrieve()
                .body(String.class);

        JsonNode root = MAPPER.readTree(stripSseFraming(body));
        if (root.has("error")) {
            throw new IllegalStateException("A2A 返回错误：" + root.get("error").toString());
        }
        return root.path("result");
    }

    // ------------------------------------------------------------------ 收尾

    private DelegationContracts.Result toResult(SubAgentEntity agent, List<String> evidence,
                                                String state, String taskId, String contextId,
                                                String prompt, long started) {
        long latency = elapsed(started);
        int steps = evidence.size();

        if (A2aProtocol.needsInput(state)) {
            String question = prompt == null || prompt.isBlank()
                    ? "远程子 Agent %s 需要确认后才能继续。".formatted(agent.getName()) : prompt;
            return DelegationContracts.Result.inputRequired(evidence, steps, latency,
                    taskId, contextId, question);
        }
        if (A2aProtocol.COMPLETED.equals(state)) {
            if (evidence.isEmpty()) {
                evidence.add("[%s] 任务已完成，但未回传任何产出。".formatted(agent.getName()));
            }
            return DelegationContracts.Result.completed(evidence, steps, latency, taskId, contextId);
        }

        String errorType = switch (state) {
            case A2aProtocol.REJECTED -> "rejected_by_remote";
            case A2aProtocol.CANCELED -> "canceled";
            default -> "remote_failed";
        };
        if (evidence.isEmpty()) {
            evidence.add("[%s] 远程任务以 %s 结束。".formatted(agent.getName(), state));
        }
        return DelegationContracts.Result.failed(errorType,
                "远程任务状态 " + state, evidence, steps, latency);
    }

    private void recordSpan(SubAgentEntity agent, DelegationContracts.Request request,
                            DelegationContracts.Result result) {
        TraceCollector collector = TraceContext.peek();
        if (collector == null) return;
        collector.addSpan("远程子 Agent " + agent.getName(), "agent",
                Map.of("endpoint", agent.getEndpoint(), "task", request.task(),
                        "resumed", request.isResume()),
                Map.of("status", result.status(), "task_id", result.remoteTaskId(),
                        "evidence", result.steps()),
                result.failed() ? "failed" : "success", result.errorMessage(), result.latencyMs());
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 请求头：注册凭证 + trace 透传。
     *
     * <p>凭证由平台在网关侧注入，业务方注册时只填配置 —— 与 MCP Server 一致。
     */
    private Map<String, String> requestHeaders(SubAgentEntity agent,
                                               DelegationContracts.Request request) {
        Map<String, String> headers = new LinkedHashMap<>();
        agent.getHeaders().forEach((k, v) -> headers.put(k, String.valueOf(v)));

        Map<String, Object> auth = agent.getAuthConfig();
        switch (agent.getAuthType() == null ? "none" : agent.getAuthType()) {
            case "bearer", "oauth2" -> {
                Object token = auth.get("token") != null ? auth.get("token") : auth.get("access_token");
                if (token != null) headers.put("Authorization", "Bearer " + token);
            }
            case "api_key" -> {
                Object apiKey = auth.get("key");
                if (apiKey != null) {
                    headers.put(String.valueOf(auth.getOrDefault("header", "X-API-Key")),
                            String.valueOf(apiKey));
                }
            }
            default -> { /* none：不注入 */ }
        }

        // W3C 追踪透传。A2A 未规定它，远端配合与否都不影响调用成功
        String traceId = request.traceId();
        if (traceId != null && traceId.length() == 32) {
            headers.put("traceparent", "00-%s-%016x-01"
                    .formatted(traceId, ThreadLocalRandom.current().nextLong() >>> 1));
        }
        return headers;
    }

    /** 部分实现即便对 message/send 也以 SSE 帧回包，取最后一个 data: 行。 */
    private String stripSseFraming(String body) {
        if (body == null || body.isBlank()) return "{}";
        if (!body.startsWith("event:") && !body.contains("\ndata:")) return body;
        String last = "{}";
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) last = line.substring(5).trim();
        }
        return last;
    }

    private String classify(Exception e) {
        if (e instanceof CircuitOpenException) return "circuit_open";
        String name = e.getClass().getSimpleName();
        if (name.contains("Timeout") || name.contains("SocketTimeout")) return "timeout";
        if (name.contains("Connect") || name.contains("UnknownHost")) return "unreachable";
        return "transport_error";
    }

    private String truncate(String text) {
        if (text == null) return "";
        String stripped = text.strip();
        return stripped.length() <= 400 ? stripped : stripped.substring(0, 400) + "…";
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
