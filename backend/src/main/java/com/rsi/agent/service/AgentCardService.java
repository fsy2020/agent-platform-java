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
import com.rsi.agent.domain.JsonAttributeConverter;
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

    // 用真正的列转换器算长度，而不是另起一个 ObjectMapper 估算 ——
    // 估算值与 JPA 实际写入的文本一旦有出入，差的那几个字符就是一个 500
    private static final JsonAttributeConverter.StringListConverter STRING_LIST =
            new JsonAttributeConverter.StringListConverter();
    private static final JsonAttributeConverter.MapListConverter MAP_LIST =
            new JsonAttributeConverter.MapListConverter();

    /** 各 skill 的 examples / tags 合并后的条数上限 —— 再多对路由匹配也没有增益。 */
    private static final int EXAMPLE_COUNT = 12;
    private static final int TAG_COUNT = 16;

    // 单条文本的上限。先按条裁剪、再按列宽丢尾：否则远端写了一条超长 example，
    // 序列化后一条就把整列占满，其余条目全被丢光，正样例反而更差。
    // 这些值也保证了「最小的一条」一定塞得进对应的列，不至于裁成空集合。
    private static final int EXAMPLE_LENGTH = 500;
    private static final int TAG_LENGTH = 64;
    private static final int SKILL_ID_LENGTH = 128;
    private static final int SKILL_DESCRIPTION_LENGTH = 500;
    private static final int SKILL_EXAMPLE_LENGTH = 200;
    private static final int SKILL_TAG_COUNT = 8;
    private static final int SKILL_EXAMPLE_COUNT = 4;

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
        // 端点与卡片地址是调用链的硬依赖：截断只会得到一个打不通的地址，
        // 与其存进去等运行时报错，不如在注册/刷新时就拒掉
        String endpoint = requireFits(card.url(), SubAgentEntity.TEXT_LENGTH, "卡片 url");
        String source = requireFits(cardUrl, SubAgentEntity.TEXT_LENGTH, "card_url");

        agent.setTransport("a2a");
        agent.setEndpoint(endpoint);
        agent.setCardUrl(source);
        agent.setProtocolVersion(cap(card.protocolVersion(), SubAgentEntity.TEXT_LENGTH));
        agent.setStreaming(card.streaming());
        agent.setCardFetchedAt(Instant.now());
        agent.setHealthStatus("healthy");
        agent.setLastError("");

        if (agent.getDisplayName() == null || agent.getDisplayName().isBlank()) {
            agent.setDisplayName(cap(card.name(), SubAgentEntity.TEXT_LENGTH));
        }
        agent.setDescription(fitText(agent, "description",
                card.routingDescription(), SubAgentEntity.DESCRIPTION_LENGTH));
        if (card.version() != null && !card.version().isBlank()) {
            agent.setVersion(cap(card.version(), SubAgentEntity.TEXT_LENGTH));
        }

        List<String> examples = card.allExamples();
        if (!examples.isEmpty()) {
            agent.setPositiveExamples(fitJson(agent, "positive_examples",
                    capEach(examples, EXAMPLE_COUNT, EXAMPLE_LENGTH),
                    SubAgentEntity.POSITIVE_EXAMPLES_LENGTH, STRING_LIST));
        }
        List<String> tags = card.allTags();
        if (!tags.isEmpty()) {
            agent.setTags(fitJson(agent, "tags",
                    capEach(tags, TAG_COUNT, TAG_LENGTH),
                    SubAgentEntity.TAGS_LENGTH, STRING_LIST));
        }

        List<Map<String, Object>> skills = new ArrayList<>();
        for (AgentCard.Skill skill : card.skills()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", cap(skill.id(), SKILL_ID_LENGTH));
            row.put("name", cap(skill.name(), SKILL_ID_LENGTH));
            row.put("description", cap(skill.description(), SKILL_DESCRIPTION_LENGTH));
            row.put("tags", capEach(skill.tags(), SKILL_TAG_COUNT, TAG_LENGTH));
            row.put("examples", capEach(skill.examples(), SKILL_EXAMPLE_COUNT, SKILL_EXAMPLE_LENGTH));
            skills.add(row);
        }
        agent.setRemoteSkills(fitJson(agent, "remote_skills", skills,
                SubAgentEntity.REMOTE_SKILLS_LENGTH, MAP_LIST));
    }

    /**
     * 裁到列宽以内。
     *
     * <p>卡片是外部产物，长度不由平台说了算：一份 skills 写得足够详尽的卡片，
     * 序列化后轻松超过列宽，save 时炸成 {@code DataIntegrityViolationException} ——
     * 那是一个 500，注册方从响应里根本看不出是哪个字段太长。这里在写入前就裁掉并留日志，
     * 把「卡片写太长」降级成能力清单不完整，而不是整次注册/刷新失败。
     *
     * <p>裁剪一律从尾部开始：卡片按重要性从前往后排是通行写法，末尾条目丢失的代价最小。
     */
    private String fitText(SubAgentEntity agent, String field, String text, int limit) {
        String value = cap(text, limit);
        if (text != null && value.length() < text.strip().length()) {
            log.warn("子 Agent {} 的 {} 超出列宽 {}（实际 {}），已截断",
                    agent.getName(), field, limit, text.strip().length());
        }
        return value;
    }

    /** 逐条丢弃末尾元素，直到 JSON 序列化结果落进列宽。 */
    private <T> List<T> fitJson(SubAgentEntity agent, String field, List<T> items, int limit,
                                JsonAttributeConverter<List<T>> converter) {
        List<T> kept = new ArrayList<>(items);
        while (!kept.isEmpty() && converter.convertToDatabaseColumn(kept).length() > limit) {
            kept.remove(kept.size() - 1);
        }
        if (kept.size() < items.size()) {
            log.warn("子 Agent {} 的 {} 序列化后超出列宽 {}，已丢弃末尾 {} 项",
                    agent.getName(), field, limit, items.size() - kept.size());
        }
        return List.copyOf(kept);
    }

    /** 超长即拒。用于截断后会失去意义的字段（地址一类）。 */
    private String requireFits(String value, int limit, String field) {
        String text = value == null ? "" : value.strip();
        if (text.length() > limit) {
            throw BizException.badRequest(
                    "%s 长度 %d 超出上限 %d 字符".formatted(field, text.length(), limit));
        }
        return text;
    }

    private static List<String> capEach(List<String> values, int maxCount, int maxLength) {
        return values.stream().limit(maxCount).map(v -> cap(v, maxLength)).toList();
    }

    /** 末位换成省略号，让「这里被裁过」在前端与审计记录里可见。 */
    private static String cap(String text, int limit) {
        String value = text == null ? "" : text.strip();
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    /** 健康探活：能拉到卡片且端点未变即视为健康。 */
    public String[] healthCheck(SubAgentEntity agent) {
        String source = agent.getCardUrl() == null || agent.getCardUrl().isBlank()
                ? agent.getEndpoint() : agent.getCardUrl();
        try {
            AgentCard card = fetch(source, null);
            if (!card.url().strip().equals(agent.getEndpoint())) {
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
