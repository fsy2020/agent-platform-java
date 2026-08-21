package com.rsi.agent.mcp.transport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.mcp.ToolDescriptor;
import com.rsi.agent.mcp.ToolTransport;
import org.springframework.stereotype.Component;

/**
 * stdio 传输：拉起本地 MCP Server 进程，按行收发 JSON-RPC。
 *
 * <p>每次调用起一个短生命周期进程，换取隔离性与实现简单 —— 工具执行隔离
 * 的最朴素形态。高频场景可改为常驻进程池，接口不变。
 */
@Component
public class StdioTransport implements ToolTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int timeoutSeconds;

    public StdioTransport(AgentProperties properties) {
        this.timeoutSeconds = properties.resilience().toolTimeoutSeconds();
    }

    @Override
    public String transport() { return "stdio"; }

    @Override
    public List<ToolDescriptor> discover(McpServerEntity server) throws Exception {
        JsonNode result = exchange(server, JsonRpcSupport.listToolsRequest(2));
        return JsonRpcSupport.parseTools(server.getName(), result);
    }

    @Override
    public Object call(McpServerEntity server, String toolName, Map<String, Object> args) throws Exception {
        JsonNode result = exchange(server, JsonRpcSupport.callToolRequest(2, toolName, args));
        if (result.path("isError").asBoolean(false)) {
            return Map.of("error", "tool_error", "message", result.toString());
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if (block.hasNonNull("text")) text.append(block.get("text").asText());
        }
        try {
            return MAPPER.readValue(text.toString(), Map.class);
        } catch (Exception ignored) {
            return Map.of("text", text.toString());
        }
    }

    /** 握手 initialize，再发一条业务请求，读取 id 匹配的响应。 */
    private JsonNode exchange(McpServerEntity server, Map<String, Object> request) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(server.getCommand());
        command.addAll(server.getArgs());

        ProcessBuilder builder = new ProcessBuilder(command);
        server.getEnv().forEach((k, v) -> builder.environment().put(k, String.valueOf(v)));
        builder.redirectErrorStream(false);

        Process process = builder.start();
        try (BufferedWriter stdin = new BufferedWriter(
                     new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader stdout = new BufferedReader(
                     new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            writeLine(stdin, JsonRpcSupport.initializeRequest(1));
            writeLine(stdin, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            writeLine(stdin, request);

            int wantedId = (int) request.get("id");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            String line;
            while (System.nanoTime() < deadline && (line = stdout.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = MAPPER.readTree(line);
                if (node.path("id").asInt(-1) != wantedId) continue;
                if (node.has("error")) {
                    throw new IllegalStateException("MCP 返回错误：" + node.get("error"));
                }
                return node.path("result");
            }
            throw new IllegalStateException("stdio MCP Server 未在超时内返回响应");
        } finally {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private void writeLine(BufferedWriter writer, Map<String, Object> payload) throws Exception {
        writer.write(MAPPER.writeValueAsString(payload));
        writer.newLine();
        writer.flush();
    }
}
