package com.rsi.agent.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.domain.McpToolEntity;
import com.rsi.agent.domain.SkillEntity;
import com.rsi.agent.domain.SubAgentEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册中心相关 DTO。
 *
 * <p>全部用 record：不可变、无样板 getter、天然适合做出参。字段名保持 camelCase，
 * 由 Jackson 的全局 SNAKE_CASE 策略统一转换成 snake_case 出网 —— 命名转换是
 * 序列化层的职责，不该污染 Java 侧的命名习惯。
 */
public final class RegistryDtos {

    private RegistryDtos() {}

    // ---------------------------------------------------------------- MCP

    public record McpToolDto(
            String id, String serverId, String name, String displayName, String description,
            Map<String, Object> inputSchema, String riskLevel, List<String> requiredScopes,
            boolean readOnly, boolean enabled, long callCount, long successCount, long totalLatencyMs
    ) {
        public static McpToolDto from(McpToolEntity tool) {
            return new McpToolDto(tool.getId(), tool.getServerId(), tool.getName(),
                    tool.getDisplayName(), tool.getDescription(), tool.getInputSchema(),
                    tool.getRiskLevel(), tool.getRequiredScopes(), tool.isReadOnly(),
                    tool.isEnabled(), tool.getCallCount(), tool.getSuccessCount(),
                    tool.getTotalLatencyMs());
        }
    }

    public record McpServerDto(
            String id, String name, String displayName, String description, String businessDomain,
            String transport, String endpoint, String command, List<String> args, String authType,
            String owner, List<String> tags, boolean enabled, String healthStatus,
            Instant lastHealthAt, String lastError, String circuitState, Instant createdAt,
            List<McpToolDto> tools
    ) {
        public static McpServerDto from(McpServerEntity server, List<McpToolEntity> tools,
                                        String circuitState) {
            return new McpServerDto(server.getId(), server.getName(), server.getDisplayName(),
                    server.getDescription(), server.getBusinessDomain(), server.getTransport(),
                    server.getEndpoint(), server.getCommand(), server.getArgs(), server.getAuthType(),
                    server.getOwner(), server.getTags(), server.isEnabled(), server.getHealthStatus(),
                    server.getLastHealthAt(), server.getLastError(), circuitState,
                    server.getCreatedAt(), tools.stream().map(McpToolDto::from).toList());
        }
    }

    public record McpServerCreateRequest(
            @NotBlank(message = "名称不能为空") String name,
            String displayName, String description, String businessDomain,
            String transport, String endpoint, String command,
            List<String> args, Map<String, Object> env, Map<String, Object> headers,
            String authType, Map<String, Object> authConfig,
            String owner, List<String> tags, Boolean enabled
    ) {}

    public record McpServerUpdateRequest(
            String displayName, String description, String businessDomain, String endpoint,
            String command, List<String> args, Map<String, Object> env, Map<String, Object> headers,
            String authType, Map<String, Object> authConfig, String owner, List<String> tags,
            Boolean enabled
    ) {}

    public record McpToolUpdateRequest(
            String displayName, String description, String riskLevel,
            List<String> requiredScopes, Boolean readOnly, Boolean enabled
    ) {}

    public record ToolInvokeRequest(Map<String, Object> arguments) {}

    // ---------------------------------------------------------------- Skill

    public record SkillDto(
            String id, String name, String displayName, String description, String businessDomain,
            List<Map<String, Object>> steps, Map<String, Object> inputSchema, String outputTemplate,
            String riskLevel, List<String> requiredScopes, List<String> examples,
            List<String> negativeExamples, String owner, String version, String status,
            boolean enabled, List<String> tags, long callCount, long successCount,
            Instant createdAt, Instant updatedAt
    ) {
        public static SkillDto from(SkillEntity skill) {
            return new SkillDto(skill.getId(), skill.getName(), skill.getDisplayName(),
                    skill.getDescription(), skill.getBusinessDomain(), skill.getSteps(),
                    skill.getInputSchema(), skill.getOutputTemplate(), skill.getRiskLevel(),
                    skill.getRequiredScopes(), skill.getExamples(), skill.getNegativeExamples(),
                    skill.getOwner(), skill.getVersion(), skill.getStatus(), skill.isEnabled(),
                    skill.getTags(), skill.getCallCount(), skill.getSuccessCount(),
                    skill.getCreatedAt(), skill.getUpdatedAt());
        }
    }

    public record SkillSaveRequest(
            String name, String displayName,
            @Size(min = 1, message = "描述不能为空") String description,
            String businessDomain, List<Map<String, Object>> steps,
            Map<String, Object> inputSchema, String outputTemplate, String riskLevel,
            List<String> requiredScopes, List<String> examples, List<String> negativeExamples,
            String owner, String version, String status, Boolean enabled, List<String> tags
    ) {}

    // ---------------------------------------------------------------- 子 Agent

    public record SubAgentDto(
            String id, String name, String displayName, String description, String businessDomain,
            String systemPrompt, List<String> toolAllowlist, List<String> skillNames,
            List<String> requiredScopes, List<String> positiveExamples, List<String> negativeExamples,
            List<String> usageExamples, String owner, String version, String riskLevel, String status,
            boolean enabled, String tenantId, String model, int maxIterations, List<String> tags,
            long callCount, long successCount, Instant createdAt, Instant updatedAt,
            // --- 远程接入（transport = a2a）---
            String transport, String endpoint, String cardUrl, String protocolVersion,
            boolean streaming, String authType, boolean credentialConfigured,
            List<Map<String, Object>> remoteSkills, Instant cardFetchedAt,
            String healthStatus, String lastError
    ) {
        /**
         * <b>authConfig 永不出现在这里。</b>凭证只存库、只在网关注入请求头，
         * 不经由任何读接口回流 —— 注册页只回显"是否已配置"。
         */
        public static SubAgentDto from(SubAgentEntity agent) {
            return new SubAgentDto(agent.getId(), agent.getName(), agent.getDisplayName(),
                    agent.getDescription(), agent.getBusinessDomain(), agent.getSystemPrompt(),
                    agent.getToolAllowlist(), agent.getSkillNames(), agent.getRequiredScopes(),
                    agent.getPositiveExamples(), agent.getNegativeExamples(), agent.getUsageExamples(),
                    agent.getOwner(), agent.getVersion(), agent.getRiskLevel(), agent.getStatus(),
                    agent.isEnabled(), agent.getTenantId(), agent.getModel(), agent.getMaxIterations(),
                    agent.getTags(), agent.getCallCount(), agent.getSuccessCount(),
                    agent.getCreatedAt(), agent.getUpdatedAt(),
                    agent.getTransport(), agent.getEndpoint(), agent.getCardUrl(),
                    agent.getProtocolVersion(), agent.isStreaming(), agent.getAuthType(),
                    agent.getAuthConfig() != null && !agent.getAuthConfig().isEmpty(),
                    agent.getRemoteSkills(), agent.getCardFetchedAt(),
                    agent.getHealthStatus(), agent.getLastError());
        }
    }

    public record SubAgentSaveRequest(
            String name, String displayName, String description, String businessDomain,
            String systemPrompt, List<String> toolAllowlist, List<String> skillNames,
            List<String> requiredScopes, List<String> positiveExamples, List<String> negativeExamples,
            List<String> usageExamples, String owner, String version, String riskLevel, String status,
            Boolean enabled, String tenantId, String model, Integer maxIterations, List<String> tags,
            String transport, String endpoint, String authType,
            Map<String, Object> authConfig, Map<String, Object> headers
    ) {}

    /**
     * A2A 子 Agent 注册请求。
     *
     * <p>业务方只需交出卡片地址与凭证；名称、描述、能力、正样例全部由
     * Agent Card 自描述填充。平台侧治理属性（风险等级、所需 scope、租户）
     * 仍由注册方在这里显式声明 —— 那不是远端能自证的东西。
     */
    public record A2aRegisterRequest(
            String cardUrl,
            String name,
            String businessDomain,
            String riskLevel,
            List<String> requiredScopes,
            List<String> negativeExamples,
            String tenantId,
            String owner,
            String authType,
            Map<String, Object> authConfig,
            Map<String, Object> headers
    ) {}
}
