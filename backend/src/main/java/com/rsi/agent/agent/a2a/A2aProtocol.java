package com.rsi.agent.agent.a2a;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A2A 协议报文层 —— 构造与解析集中在这里，传输层只管收发。
 *
 * <p>与 MCP 一样走 JSON-RPC 2.0，方法集为
 * {@code message/send}、{@code message/stream}、{@code tasks/get}、{@code tasks/cancel}。
 * 对标 {@code mcp.transport.JsonRpcSupport} 的职责划分。
 */
public final class A2aProtocol {

    /** 平台实现所对标的协议版本。远端声明不同版本时只告警不拒绝。 */
    public static final String PROTOCOL_VERSION = "0.3.0";

    /** 规范约定的卡片路径。早期规范用 agent.json，按顺序回退。 */
    public static final List<String> CARD_PATHS =
            List.of("/.well-known/agent-card.json", "/.well-known/agent.json");

    // --- Task 状态。前四个为非终态，后四个为终态 ---
    public static final String SUBMITTED = "submitted";
    public static final String WORKING = "working";
    public static final String INPUT_REQUIRED = "input-required";
    public static final String AUTH_REQUIRED = "auth-required";
    public static final String COMPLETED = "completed";
    public static final String CANCELED = "canceled";
    public static final String FAILED = "failed";
    public static final String REJECTED = "rejected";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private A2aProtocol() {}

    // ------------------------------------------------------------------ 构造

    /**
     * 构造一条 user 消息。
     *
     * <p>{@code taskId} / {@code contextId} 非空表示这是对既有任务的续发
     * —— 审批放行后的恢复正是走这条路径，而不是新建任务。
     */
    public static Map<String, Object> userMessage(String text, String taskId, String contextId,
                                                  Map<String, Object> metadata) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("kind", "message");
        message.put("role", "user");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("parts", List.of(Map.of("kind", "text", "text", text == null ? "" : text)));
        if (taskId != null && !taskId.isBlank()) message.put("taskId", taskId);
        if (contextId != null && !contextId.isBlank()) message.put("contextId", contextId);
        if (metadata != null && !metadata.isEmpty()) message.put("metadata", metadata);
        return message;
    }

    public static Map<String, Object> sendRequest(String id, Map<String, Object> message) {
        return rpc(id, "message/send", Map.of(
                "message", message,
                "configuration", Map.of(
                        "acceptedOutputModes", List.of("text/plain", "application/json"),
                        "blocking", true)));
    }

    public static Map<String, Object> streamRequest(String id, Map<String, Object> message) {
        return rpc(id, "message/stream", Map.of(
                "message", message,
                "configuration", Map.of(
                        "acceptedOutputModes", List.of("text/plain", "application/json"))));
    }

    public static Map<String, Object> getTaskRequest(String id, String taskId) {
        return rpc(id, "tasks/get", Map.of("id", taskId));
    }

    public static Map<String, Object> cancelTaskRequest(String id, String taskId) {
        return rpc(id, "tasks/cancel", Map.of("id", taskId));
    }

    private static Map<String, Object> rpc(String id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 流式事件的归一化形态。
     *
     * <p>A2A 的 {@code message/stream} 会混发四种 result：初始 Task、status-update、
     * artifact-update、以及无任务的直接 message。这里把它们压成一个结构，
     * 让传输层只写一个 switch。
     */
    public record Event(
            String kind,
            String taskId,
            String contextId,
            String state,
            String text,
            String artifactName,
            boolean terminal
    ) {}

    /** 非流式调用返回的任务快照。 */
    public record TaskSnapshot(
            String taskId,
            String contextId,
            String state,
            String statusText,
            List<String> artifacts
    ) {}

    /** 终态：不会再有后续事件，可以结束这次委派。 */
    public static boolean isTerminal(String state) {
        return COMPLETED.equals(state) || CANCELED.equals(state)
                || FAILED.equals(state) || REJECTED.equals(state);
    }

    /** 需要人工介入：转成平台的审批 / 澄清挂起。 */
    public static boolean needsInput(String state) {
        return INPUT_REQUIRED.equals(state) || AUTH_REQUIRED.equals(state);
    }

    /**
     * 解析一条流式 result。
     *
     * <p>{@code kind} 缺失时按有无 {@code status} 字段推断 —— 部分实现不写 kind。
     */
    public static Event parseEvent(JsonNode result) {
        String kind = result.path("kind").asText("");
        if (kind.isBlank()) {
            if (result.has("artifact")) {
                kind = "artifact-update";
            } else if (result.has("status")) {
                kind = result.has("history") ? "task" : "status-update";
            } else {
                kind = "message";
            }
        }

        String taskId = firstNonBlank(result.path("taskId").asText(""), result.path("id").asText(""));
        String contextId = result.path("contextId").asText("");

        return switch (kind) {
            case "artifact-update" -> {
                JsonNode artifact = result.path("artifact");
                yield new Event(kind, taskId, contextId, "",
                        textOf(artifact.path("parts")),
                        artifact.path("name").asText(""),
                        false);
            }
            case "task", "status-update" -> {
                JsonNode status = result.path("status");
                String state = status.path("state").asText("");
                boolean terminal = result.path("final").asBoolean(false) || isTerminal(state);
                yield new Event(kind, taskId, contextId, state,
                        textOf(status.path("message").path("parts")), "", terminal);
            }
            // 无任务的直接答复：远端把它当一次性问答处理，本身即终态
            default -> new Event("message", taskId, contextId, COMPLETED,
                    textOf(result.path("parts")), "", true);
        };
    }

    /** 解析 {@code message/send} / {@code tasks/get} 的 result。 */
    public static TaskSnapshot parseTask(JsonNode result) {
        String kind = result.path("kind").asText("");
        if ("message".equals(kind) || (!result.has("status") && result.has("parts"))) {
            return new TaskSnapshot("", result.path("contextId").asText(""), COMPLETED,
                    textOf(result.path("parts")), List.of());
        }

        JsonNode status = result.path("status");
        List<String> artifacts = new ArrayList<>();
        for (JsonNode artifact : result.path("artifacts")) {
            String name = artifact.path("name").asText("");
            String text = textOf(artifact.path("parts"));
            if (text.isBlank()) continue;
            artifacts.add(name.isBlank() ? text : name + "：" + text);
        }

        return new TaskSnapshot(
                result.path("id").asText(""),
                result.path("contextId").asText(""),
                status.path("state").asText(""),
                textOf(status.path("message").path("parts")),
                List.copyOf(artifacts));
    }

    /**
     * 把 parts 数组压成文本。
     *
     * <p>DataPart 直接序列化回 JSON —— 结构化结果对下游汇总节点比散文更可靠；
     * FilePart 只留一行占位，本平台的证据链不搬运二进制。
     */
    public static String textOf(JsonNode parts) {
        if (parts == null || !parts.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String kind = part.path("kind").asText("");
            switch (kind) {
                case "text" -> append(sb, part.path("text").asText(""));
                case "data" -> append(sb, part.path("data").toString());
                case "file" -> {
                    JsonNode file = part.path("file");
                    append(sb, "[文件 %s]".formatted(firstNonBlank(
                            file.path("name").asText(""), file.path("uri").asText(""))));
                }
                default -> {
                    if (part.hasNonNull("text")) append(sb, part.get("text").asText());
                }
            }
        }
        return sb.toString().strip();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        return MAPPER.convertValue(node, Map.class);
    }

    private static void append(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) return;
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(text);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }
}
