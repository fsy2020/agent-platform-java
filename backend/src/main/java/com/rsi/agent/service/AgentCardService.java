package com.rsi.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsi.agent.agent.a2a.A2aProtocol;
import com.rsi.agent.agent.a2a.AgentCard;
import com.rsi.agent.common.BizException;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.domain.SubAgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Agent Card 拉取与映射。
 *
 * <p>注册流程的核心：部门只交一个地址，平台把卡片映射成 {@link SubAgentEntity}。
 * 对标 {@code McpRegistryService} 的 {@code discover} —— 能力清单由远端自描述，
 * 不靠人工填表。
 */
@Service
public class AgentCardService {

    private static final Logger log = LoggerFactory.getLogger(AgentCardService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;

    public AgentCardService(AgentProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        int timeout = properties.a2a().cardTimeoutSeconds();
        factory.setConnectTimeout((int) Duration.ofSeconds(timeout).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeout).toMillis());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 拉取 Agent Card。
     *
     * <p>传入卡片直链则直接取；传入服务根地址则按规范路径依次尝试
     * （新版 {@code agent-card.json}，回退到早期的 {@code agent.json}）。
     */
    public AgentCard fetch(String url, Map<String, String> headers) {
        if (url == null || url.isBlank()) {
            throw BizException.badRequest("Agent Card 地址不能为空");
        }
        String trimmed = url.strip();

        List<String> attempts = trimmed.endsWith(".json")
                ? List.of(trimmed)
                : A2aProtocol.CARD_PATHS.stream()
                        .map(path -> stripTrailingSlash(trimmed) + path).toList();

        Exception last = null;
        for (String attempt : attempts) {
            try {
                String body = client.get()
                        .uri(attempt)
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(h -> {
                            if (headers != null) headers.forEach(h::set);
                        })
                        .retrieve()
                        .body(String.class);

                JsonNode node = MAPPER.readTree(body == null ? "{}" : body);
                AgentCard card = AgentCard.from(node);
                if (card.name() == null || card.name().isBlank()) {
                    throw new IllegalStateException("卡片缺少 name 字段");
                }
                if (card.url() == null || card.url().isBlank()) {
                    throw new IllegalStateException("卡片缺少 url 字段，无法确定调用端点");
                }
                warnOnVersion(card, attempt);
                log.info("已拉取 Agent Card：{}（{} 项 skill）来自 {}",
                        card.name(), card.skills().size(), attempt);
                return card;

            } catch (Exception e) {
                last = e;
                log.debug("拉取 Agent Card 失败 {}: {}", attempt, e.toString());
            }
        }
        throw BizException.badRequest("无法拉取 Agent Card（尝试过 %s）：%s".formatted(
                String.join(", ", attempts),
                last == null ? "未知错误" : last.getClass().getSimpleName() + ": " + last.getMessage()));
    }

    /**
     * 把卡片写进实体。
     *
     * <p>只覆盖<b>由卡片自描述</b>的字段。平台侧的治理属性 —— riskLevel、
     * requiredScopes、tenantId、negativeExamples、审批策略 —— 一律保留，
     * 否则远端一次改卡就能悄悄放宽自己在平台上的权限。
     */
    public void applyCard(SubAgentEntity agent, AgentCard card, String cardUrl) {
        agent.setTransport("a2a");
        agent.setEndpoint(card.url());
        agent.setCardUrl(cardUrl);
        agent.setProtocolVersion(card.protocolVersion());
        agent.setStreaming(card.streaming());
        agent.setCardFetchedAt(Instant.now());
        agent.setHealthStatus("healthy");
        agent.setLastError("");

        if (agent.getDisplayName() == null || agent.getDisplayName().isBlank()) {
            agent.setDisplayName(card.name());
        }
        agent.setDescription(card.routingDescription());
        if (card.version() != null && !card.version().isBlank()) {
            agent.setVersion(card.version());
        }

        List<String> examples = card.allExamples();
        if (!examples.isEmpty()) {
            agent.setPositiveExamples(examples.stream().limit(12).toList());
        }
        List<String> tags = card.allTags();
        if (!tags.isEmpty()) {
            agent.setTags(tags.stream().limit(16).toList());
        }

        List<Map<String, Object>> skills = new ArrayList<>();
        for (AgentCard.Skill skill : card.skills()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", skill.id());
            row.put("name", skill.name());
            row.put("description", skill.description());
            row.put("tags", skill.tags());
            row.put("examples", skill.examples());
            skills.add(row);
        }
        agent.setRemoteSkills(List.copyOf(skills));
    }

    /** 健康探活：能拉到卡片且端点未变即视为健康。 */
    public String[] healthCheck(SubAgentEntity agent) {
        String source = agent.getCardUrl() == null || agent.getCardUrl().isBlank()
                ? agent.getEndpoint() : agent.getCardUrl();
        try {
            AgentCard card = fetch(source, null);
            if (!card.url().equals(agent.getEndpoint())) {
                return new String[]{"degraded",
                        "卡片端点已变更为 %s，请重新拉取".formatted(card.url())};
            }
            return new String[]{"healthy", ""};
        } catch (Exception e) {
            return new String[]{"down", e.getClass().getSimpleName() + ": " + e.getMessage()};
        }
    }

    /** 由卡片名生成合法的注册名（唯一键与路由标识，限 64 字符）。 */
    public static String slugify(String name) {
        String slug = (name == null ? "" : name).strip().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.isBlank()) slug = "remote-agent";
        return slug.length() <= 64 ? slug : slug.substring(0, 64);
    }

    private void warnOnVersion(AgentCard card, String source) {
        String declared = card.protocolVersion();
        if (declared != null && !declared.isBlank()
                && !declared.startsWith(A2aProtocol.PROTOCOL_VERSION.substring(0, 3))) {
            log.warn("Agent Card {} 声明协议版本 {}，平台实现对标 {} —— 字段差异可能导致解析降级",
                    source, declared, A2aProtocol.PROTOCOL_VERSION);
        }
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
