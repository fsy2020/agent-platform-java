package com.rsi.agent.service;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.rsi.agent.agent.a2a.AgentCard;
import com.rsi.agent.common.BizException;
import com.rsi.agent.common.ErrorCode;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.domain.JsonAttributeConverter;
import com.rsi.agent.domain.SubAgentEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡片字段落库前的裁剪。
 *
 * <p>覆盖的是 registerA2a 与 refreshCard 共用的那一段 —— 两条链路都只经
 * {@link AgentCardService#applyCard} 写卡片字段，因此在这里拦住即两条都拦住。
 */
class AgentCardServiceTest {

    private static final int OVERSIZED_SKILL_COUNT = 20;

    private static final JsonAttributeConverter.StringListConverter STRING_LIST =
            new JsonAttributeConverter.StringListConverter();
    private static final JsonAttributeConverter.MapListConverter MAP_LIST =
            new JsonAttributeConverter.MapListConverter();

    /** a2a 段可整体缺省，其余段本用例用不到。 */
    private final AgentCardService service =
            new AgentCardService(new AgentProperties(null, null, null, null, null));

    @Test
    void oversizedCardFitsEveryColumn() {
        SubAgentEntity agent = agent();
        service.applyCard(agent, oversizedCard(), "https://hr.corp/agent");

        assertTrue(agent.getDescription().length() <= SubAgentEntity.DESCRIPTION_LENGTH);
        assertTrue(json(STRING_LIST, agent.getPositiveExamples())
                <= SubAgentEntity.POSITIVE_EXAMPLES_LENGTH);
        assertTrue(json(STRING_LIST, agent.getTags()) <= SubAgentEntity.TAGS_LENGTH);
        assertTrue(json(MAP_LIST, agent.getRemoteSkills()) <= SubAgentEntity.REMOTE_SKILLS_LENGTH);

        // 断言确实裁过，否则上面几条在「卡片本来就不长」时也能通过
        assertEquals(SubAgentEntity.DESCRIPTION_LENGTH, agent.getDescription().length());
        assertTrue(agent.getRemoteSkills().size() < OVERSIZED_SKILL_COUNT);
    }

    /** 裁到空集合会让 validateRoutingQuality 误判成「卡片没给正样例」。 */
    @Test
    void keepsAtLeastOneExampleTagAndSkill() {
        SubAgentEntity agent = agent();
        service.applyCard(agent, oversizedCard(), "https://hr.corp/agent");

        assertFalse(agent.getPositiveExamples().isEmpty());
        assertFalse(agent.getTags().isEmpty());
        assertFalse(agent.getRemoteSkills().isEmpty());
    }

    @Test
    void normalCardIsStoredVerbatim() {
        SubAgentEntity agent = agent();
        AgentCard card = card("https://hr.corp/a2a", List.of(new AgentCard.Skill(
                "leave", "请假查询", "查询员工假期余额与在途申请",
                List.of("hr", "leave"), List.of("我还有几天年假？"))));

        service.applyCard(agent, card, "https://hr.corp/agent");

        assertEquals("人事助手；请假查询：查询员工假期余额与在途申请", agent.getDescription());
        assertEquals(List.of("我还有几天年假？"), agent.getPositiveExamples());
        assertEquals(List.of("hr", "leave"), agent.getTags());
        assertEquals(1, agent.getRemoteSkills().size());
        assertEquals("https://hr.corp/a2a", agent.getEndpoint());
    }

    /** 端点截断后只会得到一个打不通的地址，必须拒绝而不是裁剪。 */
    @Test
    void rejectsOversizedEndpoint() {
        SubAgentEntity agent = agent();
        AgentCard card = card("https://hr.corp/" + "x".repeat(SubAgentEntity.TEXT_LENGTH),
                List.of());

        BizException e = assertThrows(BizException.class,
                () -> service.applyCard(agent, card, "https://hr.corp/agent"));
        assertEquals(ErrorCode.BAD_REQUEST, e.code());
    }

    private static SubAgentEntity agent() {
        SubAgentEntity agent = new SubAgentEntity();
        agent.setName("hr-assistant");
        return agent;
    }

    /** 每一项都远超列宽：description、examples、tags、skills 全部需要裁剪。 */
    private static AgentCard oversizedCard() {
        List<AgentCard.Skill> skills = IntStream.range(0, OVERSIZED_SKILL_COUNT)
                .mapToObj(i -> new AgentCard.Skill(
                        "skill-" + i,
                        "能力" + i,
                        "描述".repeat(600),
                        IntStream.range(0, 20).mapToObj(t -> "标签" + i + "-" + t).toList(),
                        IntStream.range(0, 20).mapToObj(x -> "样例".repeat(400)).toList()))
                .toList();
        return card("https://hr.corp/a2a", skills);
    }

    private static AgentCard card(String url, List<AgentCard.Skill> skills) {
        return new AgentCard("0.3.0", "人事助手", "人事助手", url, "JSONRPC", "1.2.0",
                true, false, List.of("text"), List.of("text"), skills, Map.of());
    }

    private static <T> int json(JsonAttributeConverter<List<T>> converter, List<T> value) {
        return converter.convertToDatabaseColumn(value).length();
    }
}
