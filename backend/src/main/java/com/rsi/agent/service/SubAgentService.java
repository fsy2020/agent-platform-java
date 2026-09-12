package com.rsi.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rsi.agent.agent.AgentGateway;
import com.rsi.agent.agent.CapabilityRegistry;
import com.rsi.agent.agent.CapabilitySnapshot;
import com.rsi.agent.agent.a2a.AgentCard;
import com.rsi.agent.annotation.RequiresScope;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.dto.RegistryDtos.A2aRegisterRequest;
import com.rsi.agent.dto.RegistryDtos.SubAgentDto;
import com.rsi.agent.dto.RegistryDtos.SubAgentSaveRequest;
import com.rsi.agent.repository.SkillRepository;
import com.rsi.agent.repository.SubAgentRepository;
import com.rsi.agent.security.Identity;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/** 子 Agent 注册中心：业务方动态创建并绑定 MCP 工具 / Skill。 */
@Service
public class SubAgentService {

    private final SubAgentRepository agentRepository;
    private final SkillRepository skillRepository;
    private final CapabilityRegistry capabilityRegistry;
    private final AgentCardService cardService;
    private final AgentGateway agentGateway;

    public SubAgentService(SubAgentRepository agentRepository, SkillRepository skillRepository,
                           CapabilityRegistry capabilityRegistry, AgentCardService cardService,
                           AgentGateway agentGateway) {
        this.agentRepository = agentRepository;
        this.skillRepository = skillRepository;
        this.capabilityRegistry = capabilityRegistry;
        this.cardService = cardService;
        this.agentGateway = agentGateway;
    }

    @RequiresScope("agent:read")
    public List<SubAgentDto> list() {
        return agentRepository.findAllByOrderByCreatedAtAsc().stream().map(SubAgentDto::from).toList();
    }

    @RequiresScope("agent:read")
    public SubAgentDto get(String agentId) {
        return SubAgentDto.from(require(agentId));
    }

    @RequiresScope("agent:write")
    @Transactional
    public SubAgentDto create(SubAgentSaveRequest request, Identity identity) {
        if (agentRepository.existsByName(request.name())) {
            throw BizException.conflict("子 Agent 名称已存在：" + request.name());
        }
        validateTransport(request.transport());
        boolean remote = request.transport() != null && !request.transport().isBlank()
                && !"local".equals(request.transport());
        validateBindings(request.toolAllowlist(), request.skillNames(), identity, remote);
        validateRoutingQuality(request.description(), request.positiveExamples());

        SubAgentEntity agent = new SubAgentEntity();
        agent.setName(request.name());
        apply(agent, request);
        if (agent.getOwner().isBlank()) agent.setOwner(identity.username());
        if (agent.isRemote() && agent.getEndpoint().isBlank()) {
            throw BizException.badRequest("远程子 Agent 必须提供 endpoint，或改用 /agents/register-a2a 由卡片自动填充");
        }
        agentRepository.save(agent);
        return SubAgentDto.from(agent);
    }

    /**
     * 通过 Agent Card 注册远程子 Agent。
     *
     * <p>业务方只交出卡片地址与凭证 —— 名称、描述、能力清单、正样例全部由
     * 远端自描述填充，与 MCP Server 的 discover 是同一套思路：
     * <b>能力清单必须来自能力提供方，人工填表迟早对不上。</b>
     */
    // 刻意不加 @Transactional：方法内要走一次跨网络的卡片拉取（最长 10s），
    // 包在事务里会白白占着数据库连接。只有末尾一次 save，仓库自带的事务足够。
    @RequiresScope("agent:write")
    public SubAgentDto registerA2a(A2aRegisterRequest request, Identity identity) {
        if (request.cardUrl() == null || request.cardUrl().isBlank()) {
            throw BizException.badRequest("card_url 不能为空");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (request.headers() != null) {
            request.headers().forEach((k, v) -> headers.put(k, String.valueOf(v)));
        }
        AgentCard card = cardService.fetch(request.cardUrl(), headers);

        String name = request.name() == null || request.name().isBlank()
                ? AgentCardService.slugify(card.name()) : request.name().strip();
        if (agentRepository.existsByName(name)) {
            throw BizException.conflict("子 Agent 名称已存在：" + name);
        }

        SubAgentEntity agent = new SubAgentEntity();
        agent.setName(name);
        agent.setOwner(request.owner() == null || request.owner().isBlank()
                ? identity.username() : request.owner());
        if (request.businessDomain() != null) agent.setBusinessDomain(request.businessDomain());
        if (request.tenantId() != null) agent.setTenantId(request.tenantId());
        if (request.negativeExamples() != null) agent.setNegativeExamples(request.negativeExamples());

        // 治理属性由注册方声明：远端无法自证自己该有多高权限
        agent.setRiskLevel(request.riskLevel() == null || request.riskLevel().isBlank()
                ? "medium" : request.riskLevel());
        agent.setRequiredScopes(request.requiredScopes() == null
                ? List.of() : request.requiredScopes());

        agent.setAuthType(request.authType() == null || request.authType().isBlank()
                ? "none" : request.authType());
        agent.setAuthConfig(request.authConfig() == null ? Map.of() : request.authConfig());
        agent.setHeaders(request.headers() == null ? Map.of() : request.headers());

        cardService.applyCard(agent, card, request.cardUrl());
        validateRoutingQuality(agent.getDescription(), agent.getPositiveExamples());

        agentRepository.save(agent);
        return SubAgentDto.from(agent);
    }

    /** 重新拉取卡片。远端能力变了不会自动同步，需要显式刷新 —— 这是审计要求。 */
    @RequiresScope("agent:write")
    public SubAgentDto refreshCard(String agentId) {
        SubAgentEntity agent = require(agentId);
        if (!agent.isRemote()) {
            throw BizException.badRequest("本地子 Agent 没有 Agent Card 可刷新");
        }
        String source = agent.getCardUrl() == null || agent.getCardUrl().isBlank()
                ? agent.getEndpoint() : agent.getCardUrl();

        Map<String, String> headers = new LinkedHashMap<>();
        agent.getHeaders().forEach((k, v) -> headers.put(k, String.valueOf(v)));

        AgentCard card = cardService.fetch(source, headers);
        cardService.applyCard(agent, card, source);
        agentRepository.save(agent);
        return SubAgentDto.from(agent);
    }

    /** 探活：能拉到卡片且端点未变即视为健康。 */
    @RequiresScope("agent:read")
    public Map<String, Object> health(String agentId) {
        SubAgentEntity agent = require(agentId);
        if (!agent.isRemote()) {
            return Map.of("agent", agent.getName(), "transport", "local", "status", "healthy");
        }
        String[] result = cardService.healthCheck(agent);
        agent.setHealthStatus(result[0]);
        agent.setLastError(result[1]);
        agentRepository.save(agent);
        return Map.of("agent", agent.getName(), "transport", agent.getTransport(),
                "endpoint", agent.getEndpoint(), "status", result[0], "error", result[1]);
    }

    @RequiresScope("agent:write")
    @Transactional
    public SubAgentDto update(String agentId, SubAgentSaveRequest request, Identity identity) {
        SubAgentEntity agent = require(agentId);
        validateTransport(request.transport());
        boolean remote = request.transport() == null || request.transport().isBlank()
                ? agent.isRemote() : !"local".equals(request.transport());
        validateBindings(request.toolAllowlist(), request.skillNames(), identity, remote);
        apply(agent, request);
        agentRepository.save(agent);
        return SubAgentDto.from(agent);
    }

    @RequiresScope("agent:write")
    @Transactional
    public void delete(String agentId) {
        agentRepository.delete(require(agentId));
    }

    /**
     * 运行时生效的能力集。
     *
     * <p>展示 declared → effective 的裁剪过程：声明了什么、实际拿到什么、
     * 因权限被拒了什么。这是验证「最小权限」是否真的生效的窗口。
     */
    @RequiresScope("agent:read")
    public Map<String, Object> effectiveCapabilities(String agentId, Identity identity) {
        SubAgentEntity agent = require(agentId);
        CapabilitySnapshot snapshot = capabilityRegistry.load(identity);

        // 远程 Agent：平台看不见它的工具，declared→effective 这套裁剪根本不成立。
        // 与其显示一个假的"有效工具集"，不如明说边界已经不在平台侧。
        if (agent.isRemote()) {
            Map<String, Object> remote = new LinkedHashMap<>();
            remote.put("agent", agent.getName());
            remote.put("transport", agent.getTransport());
            remote.put("endpoint", agent.getEndpoint());
            remote.put("enforcement", "remote");
            remote.put("declared_tools", agent.getToolAllowlist().stream().sorted().toList());
            remote.put("effective_tools", List.of());
            remote.put("remote_skills", agent.getRemoteSkills());
            remote.put("card_fetched_at", agent.getCardFetchedAt());
            remote.put("health_status", agent.getHealthStatus());
            remote.put("note", "远程子 Agent 使用自己的工具集，平台无法枚举或拦截。"
                    + "declared_tools 仅为注册方申报值，用于审计比对，不构成运行时边界。");
            return remote;
        }

        List<String> declared = new ArrayList<>(agent.getToolAllowlist());
        List<String> effective = capabilityRegistry.agentTools(snapshot, agent).stream()
                .map(CapabilitySnapshot.ToolEntry::qualifiedName).sorted().toList();

        List<String> denied = declared.stream()
                .filter(name -> snapshot.tool(name).isEmpty())
                .filter(name -> snapshot.deniedTools().stream().anyMatch(d -> d.equals(name)))
                .sorted().toList();
        List<String> missing = declared.stream()
                .filter(name -> snapshot.tool(name).isEmpty())
                .filter(name -> !denied.contains(name))
                .sorted().toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent.getName());
        result.put("declared_tools", declared.stream().sorted().toList());
        result.put("effective_tools", effective);
        result.put("denied_by_permission", denied);
        result.put("missing_tools", missing);
        result.put("skills", agent.getSkillNames());
        result.put("total_platform_tools", snapshot.totalToolCount());
        result.put("supervisor_visible_tools",
                capabilityRegistry.supervisorVisibleTools(snapshot).size());
        return result;
    }

    private void apply(SubAgentEntity agent, SubAgentSaveRequest request) {
        if (request.displayName() != null) agent.setDisplayName(request.displayName());
        if (request.description() != null) agent.setDescription(request.description());
        if (request.businessDomain() != null) agent.setBusinessDomain(request.businessDomain());
        if (request.systemPrompt() != null) agent.setSystemPrompt(request.systemPrompt());
        if (request.toolAllowlist() != null) agent.setToolAllowlist(request.toolAllowlist());
        if (request.skillNames() != null) agent.setSkillNames(request.skillNames());
        if (request.requiredScopes() != null) agent.setRequiredScopes(request.requiredScopes());
        if (request.positiveExamples() != null) agent.setPositiveExamples(request.positiveExamples());
        if (request.negativeExamples() != null) agent.setNegativeExamples(request.negativeExamples());
        if (request.usageExamples() != null) agent.setUsageExamples(request.usageExamples());
        if (request.owner() != null) agent.setOwner(request.owner());
        if (request.version() != null) agent.setVersion(request.version());
        if (request.riskLevel() != null) agent.setRiskLevel(request.riskLevel());
        if (request.status() != null) agent.setStatus(request.status());
        if (request.enabled() != null) agent.setEnabled(request.enabled());
        if (request.tenantId() != null) agent.setTenantId(request.tenantId());
        if (request.model() != null) agent.setModel(request.model());
        if (request.maxIterations() != null) agent.setMaxIterations(request.maxIterations());
        if (request.tags() != null) agent.setTags(request.tags());
        if (request.transport() != null) agent.setTransport(request.transport());
        if (request.endpoint() != null) agent.setEndpoint(request.endpoint());
        if (request.authType() != null) agent.setAuthType(request.authType());
        if (request.authConfig() != null) agent.setAuthConfig(request.authConfig());
        if (request.headers() != null) agent.setHeaders(request.headers());
    }

    private void validateTransport(String transport) {
        if (transport == null || transport.isBlank()) return;
        if (!agentGateway.supports(transport)) {
            throw BizException.badRequest("不支持的传输 `%s`，可用：%s"
                    .formatted(transport, agentGateway.supported()));
        }
    }

    /**
     * @param remote 远程 Agent 的工具在远端，平台无法校验也无法约束 ——
     *               此时 toolAllowlist 只是申报信息，不做存在性校验
     */
    private void validateBindings(List<String> tools, List<String> skills, Identity identity,
                                  boolean remote) {
        if (!remote && tools != null && !tools.isEmpty()) {
            CapabilitySnapshot snapshot = capabilityRegistry.load(identity);
            List<String> missing = tools.stream()
                    .filter(name -> snapshot.tool(name).isEmpty()).toList();
            if (!missing.isEmpty()) {
                throw BizException.badRequest("以下工具未注册或无权访问：" + String.join(", ", missing));
            }
        }
        if (remote && skills != null && !skills.isEmpty()) {
            throw BizException.badRequest("远程子 Agent 不能绑定平台 Skill —— 它的编排在远端自行完成");
        }
        if (skills != null && !skills.isEmpty()) {
            List<String> known = skillRepository.findByNameIn(skills).stream()
                    .map(s -> s.getName()).toList();
            List<String> missing = skills.stream().filter(name -> !known.contains(name)).toList();
            if (!missing.isEmpty()) {
                throw BizException.badRequest("以下 Skill 未注册：" + String.join(", ", missing));
            }
        }
    }

    /**
     * 路由质量前置校验。
     *
     * <p>description 是主 Agent 唯一的路由依据，正样例是 few-shot 的基线。
     * 这两项缺失会让误路由率显著升高，因此在注册时就拦住，而不是等上线后再查。
     */
    private void validateRoutingQuality(String description, List<String> positiveExamples) {
        if (description == null || description.strip().length() < 20) {
            throw BizException.badRequest(
                    "description 至少 20 字，需说明能力范围与输入输出规范，否则主 Agent 无法正确路由");
        }
        if (positiveExamples == null || positiveExamples.isEmpty()) {
            throw BizException.badRequest(
                    "必须提供至少一条 positive_examples（正样例），用于路由匹配与 few-shot");
        }
    }

    private SubAgentEntity require(String agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> BizException.notFound("子 Agent"));
    }
}
