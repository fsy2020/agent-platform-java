package com.rsi.agent.mcp.transport;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.mcp.ToolDescriptor;
import com.rsi.agent.mcp.ToolTransport;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Streamable HTTP 传输：以 JSON-RPC over HTTP 连接远程 MCP Server。
 *
 * <p>鉴权在这里统一注入（Bearer / API Key / OAuth2），业务方注册时只填配置，
 * 不需要各自实现一遍认证逻辑 —— 这正是网关下沉横切能力的意义。
 */
@Component
public class HttpTransport implements ToolTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final AtomicInteger requestId = new AtomicInteger(1);

    public HttpTransport(AgentProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(
                properties.resilience().toolTimeoutSeconds()).toMillis());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String transport() { return "streamable_http"; }

    @Override
    public List<ToolDescriptor> discover(McpServerEntity server) throws Exception {
        JsonNode result = rpc(server, JsonRpcSupport.listToolsRequest(requestId.getAndIncrement()));
        return JsonRpcSupport.parseTools(server.getName(), result);
    }

    @Override
    public Object call(McpServerEntity server, String toolName, Map<String, Object> args) throws Exception {
        JsonNode result = rpc(server,
                JsonRpcSupport.callToolRequest(requestId.getAndIncrement(), toolName, args));

        if (result.path("isError").asBoolean(false)) {
            return Map.of("error", "tool_error", "message", extractText(result));
        }
        String text = extractText(result);
        try {
            return MAPPER.readValue(text, Map.class);
        } catch (Exception ignored) {
            return Map.of("text", text);
        }
    }

    private JsonNode rpc(McpServerEntity server, Map<String, Object> payload) throws Exception {
        String body = client.post()
                .uri(server.getEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> authHeaders(server).forEach(headers::set))
                .body(payload)
                .retrieve()
                .body(String.class);

        JsonNode root = MAPPER.readTree(stripSseFraming(body));
        if (root.has("error")) {
            throw new IllegalStateException("MCP 返回错误：" + root.get("error").toString());
        }
        return root.path("result");
    }

    /** Streamable HTTP 可能以 SSE 帧回包，取最后一个 data: 行即可。 */
    private String stripSseFraming(String body) {
        if (body == null || body.isBlank()) return "{}";
        if (!body.startsWith("event:") && !body.contains("\ndata:")) return body;
        String last = "{}";
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) last = line.substring(5).trim();
        }
        return last;
    }

    private String extractText(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if (block.hasNonNull("text")) sb.append(block.get("text").asText());
        }
        return sb.toString();
    }

    /** 鉴权透传：由网关统一注入，凭证不下发到业务代码。 */
    static Map<String, String> authHeaders(McpServerEntity server) {
        Map<String, Object> config = server.getAuthConfig();
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        server.getHeaders().forEach((k, v) -> headers.put(k, String.valueOf(v)));

        switch (server.getAuthType()) {
            case "bearer" -> {
                Object token = config.get("token");
                if (token != null) headers.put("Authorization", "Bearer " + token);
            }
            case "api_key" -> {
                Object key = config.get("key");
                if (key != null) {
                    headers.put(String.valueOf(config.getOrDefault("header", "X-API-Key")),
                            String.valueOf(key));
                }
            }
            case "oauth2" -> {
                Object token = config.get("access_token");
                if (token != null) headers.put("Authorization", "Bearer " + token);
            }
            default -> { /* none：不注入 */ }
        }
        return headers;
    }
}
