package com.rsi.agent.llm;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsi.agent.config.AgentProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Claude Messages API 客户端。
 *
 * <p>只做一件事：把请求发出去、把文本取回来。重试、切备用、降级都在
 * {@link LlmRouter} 里，保持这一层薄而可替换。
 */
@Component
public class AnthropicClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final AgentProperties.Llm config;

    public AnthropicClient(AgentProperties properties) {
        this.config = properties.llm();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** 单轮补全。返回 [文本, 输入token, 输出token]。 */
    public Completion complete(String model, String system, String user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", config.maxTokens());
        payload.put("system", system);
        payload.put("messages", List.of(Map.of("role", "user", "content", user)));
        // 自适应思考 + effort：由模型自行决定思考深度，兼顾质量与成本
        payload.put("thinking", Map.of("type", "adaptive"));
        payload.put("output_config", Map.of("effort", config.effort()));

        String body = client.post()
                .uri(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", API_VERSION)
                .body(payload)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = MAPPER.readTree(body);
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            JsonNode usage = root.path("usage");
            return new Completion(text.toString(),
                    usage.path("input_tokens").asInt(0),
                    usage.path("output_tokens").asInt(0));
        } catch (Exception e) {
            throw new IllegalStateException("解析模型响应失败: " + e.getMessage(), e);
        }
    }

    public record Completion(String text, int promptTokens, int completionTokens) {}
}
