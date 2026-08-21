package com.rsi.agent.mcp.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsi.agent.mcp.ToolDescriptor;

/** MCP 走的是 JSON-RPC 2.0。这里集中处理报文构造与 tools/list 结果解析。 */
final class JsonRpcSupport {

    private JsonRpcSupport() {}

    static Map<String, Object> listToolsRequest(int id) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", "tools/list", "params", Map.of());
    }

    static Map<String, Object> callToolRequest(int id, String tool, Map<String, Object> args) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", args == null ? Map.of() : args));
    }

    static Map<String, Object> initializeRequest(int id) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "rsi-agent-platform", "version", "1.0.0")));
    }

    /** 解析 tools/list 结果。远端不给风险等级，按「写动词」启发式推断。 */
    static List<ToolDescriptor> parseTools(String serverName, JsonNode result) {
        List<ToolDescriptor> tools = new ArrayList<>();
        JsonNode array = result.path("tools");
        for (JsonNode node : array) {
            String name = node.path("name").asText();
            String description = node.path("description").asText("");
            JsonNode schema = node.has("inputSchema") ? node.get("inputSchema") : node.get("input_schema");

            boolean write = isWriteVerb(name);
            tools.add(new ToolDescriptor(
                    serverName, name, description,
                    schema == null || schema.isNull()
                            ? Map.of("type", "object", "properties", Map.of(), "required", List.of())
                            : JsonRpcSupport.toMap(schema),
                    write ? "high" : "low",
                    !write,
                    List.of()));
        }
        return tools;
    }

    static boolean isWriteVerb(String toolName) {
        String lower = toolName.toLowerCase();
        return lower.contains("create") || lower.contains("update") || lower.contains("delete")
                || lower.contains("submit") || lower.contains("reset") || lower.contains("send")
                || lower.contains("approve") || lower.contains("cancel") || lower.contains("write");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(JsonNode node) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(node, Map.class);
    }
}
