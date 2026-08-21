package com.rsi.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rsi.agent.annotation.RequiresScope;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.domain.McpToolEntity;
import com.rsi.agent.dto.RegistryDtos.McpServerCreateRequest;
import com.rsi.agent.dto.RegistryDtos.McpServerDto;
import com.rsi.agent.dto.RegistryDtos.McpServerUpdateRequest;
import com.rsi.agent.dto.RegistryDtos.McpToolDto;
import com.rsi.agent.dto.RegistryDtos.McpToolUpdateRequest;
import com.rsi.agent.mcp.BuiltinToolRegistry;
import com.rsi.agent.mcp.CircuitBreaker;
import com.rsi.agent.mcp.McpGateway;
import com.rsi.agent.mcp.ToolDescriptor;
import com.rsi.agent.mcp.ToolResult;
import com.rsi.agent.repository.McpServerRepository;
import com.rsi.agent.repository.McpToolRepository;
import com.rsi.agent.security.Identity;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/**
 * MCP 注册中心。
 *
 * <p>注意方法上的 {@code @RequiresScope} —— 权限校验由切面完成，本类里
 * 一行 if-role 判断都没有。这让「谁能改注册中心」这条规则集中、可读、可审计。
 */
@Service
public class McpRegistryService {

    private static final List<String> VALID_TRANSPORTS =
            List.of("builtin", "stdio", "streamable_http", "sse");

    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final McpGateway gateway;
    private final BuiltinToolRegistry builtinRegistry;
    private final CircuitBreaker circuitBreaker;

    public McpRegistryService(McpServerRepository serverRepository, McpToolRepository toolRepository,
                              McpGateway gateway, BuiltinToolRegistry builtinRegistry,
                              CircuitBreaker circuitBreaker) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.gateway = gateway;
        this.builtinRegistry = builtinRegistry;
        this.circuitBreaker = circuitBreaker;
    }

    /** 内置可注册的 Server 目录 —— 由反射扫描结果直接生成，不存在「文档与实现不一致」。 */
    @RequiresScope("mcp:read")
    public List<Map<String, Object>> builtinCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (BuiltinToolRegistry.ServerMeta meta : builtinRegistry.servers()) {
            List<ToolDescriptor> descriptors = builtinRegistry.descriptors(meta.name());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", meta.name());
            item.put("display_name", meta.displayName());
            item.put("description", meta.description());
            item.put("business_domain", meta.domain());
            item.put("tool_count", descriptors.size());
            item.put("tools", descriptors.stream().map(descriptor -> Map.of(
                    "name", descriptor.name(),
                    "description", descriptor.description(),
                    "risk_level", descriptor.risk())).toList());
            catalog.add(item);
        }
        return catalog;
    }

    @RequiresScope("mcp:read")
    public List<McpServerDto> listServers() {
        return serverRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(server -> McpServerDto.from(server,
                        toolRepository.findByServerId(server.getId()),
                        circuitBreaker.state(server.getName()).name().toLowerCase()))
                .toList();
    }

    @RequiresScope("mcp:write")
    @Transactional
    public McpServerDto createServer(McpServerCreateRequest request, Identity identity) {
        String transport = orDefault(request.transport(), "builtin");
        if (!VALID_TRANSPORTS.contains(transport)) {
            throw BizException.badRequest("不支持的传输方式：" + transport);
        }
        if ("builtin".equals(transport) && !builtinRegistry.hasServer(request.name())) {
            throw BizException.badRequest("内置 Server 不存在：" + request.name());
        }
        if (serverRepository.existsByName(request.name())) {
            throw BizException.conflict("Server 名称已存在：" + request.name());
        }

        McpServerEntity server = new McpServerEntity();
        server.setName(request.name());
        server.setDisplayName(orDefault(request.displayName(), request.name()));
        server.setDescription(orDefault(request.description(), ""));
        server.setBusinessDomain(orDefault(request.businessDomain(), "general"));
        server.setTransport(transport);
        server.setEndpoint(orDefault(request.endpoint(), ""));
        server.setCommand(orDefault(request.command(), ""));
        server.setArgs(request.args() == null ? List.of() : request.args());
        server.setEnv(request.env() == null ? Map.of() : request.env());
        server.setHeaders(request.headers() == null ? Map.of() : request.headers());
        server.setAuthType(orDefault(request.authType(), "none"));
        server.setAuthConfig(request.authConfig() == null ? Map.of() : request.authConfig());
        server.setOwner(orDefault(request.owner(), identity.username()));
        server.setTags(request.tags() == null ? List.of() : request.tags());
        server.setEnabled(request.enabled() == null || request.enabled());
        serverRepository.save(server);

        syncTools(server);
        return McpServerDto.from(server, toolRepository.findByServerId(server.getId()), "closed");
    }

    @RequiresScope("mcp:write")
    @Transactional
    public McpServerDto updateServer(String serverId, McpServerUpdateRequest request) {
        McpServerEntity server = requireServer(serverId);

        if (request.displayName() != null) server.setDisplayName(request.displayName());
        if (request.description() != null) server.setDescription(request.description());
        if (request.businessDomain() != null) server.setBusinessDomain(request.businessDomain());
        if (request.endpoint() != null) server.setEndpoint(request.endpoint());
        if (request.command() != null) server.setCommand(request.command());
        if (request.args() != null) server.setArgs(request.args());
        if (request.env() != null) server.setEnv(request.env());
        if (request.headers() != null) server.setHeaders(request.headers());
        if (request.authType() != null) server.setAuthType(request.authType());
        if (request.authConfig() != null) server.setAuthConfig(request.authConfig());
        if (request.owner() != null) server.setOwner(request.owner());
        if (request.tags() != null) server.setTags(request.tags());
        if (request.enabled() != null) server.setEnabled(request.enabled());

        serverRepository.save(server);
        return McpServerDto.from(server, toolRepository.findByServerId(server.getId()),
                circuitBreaker.state(server.getName()).name().toLowerCase());
    }

    @RequiresScope("mcp:write")
    @Transactional
    public void deleteServer(String serverId) {
        McpServerEntity server = requireServer(serverId);
        circuitBreaker.reset(server.getName());
        toolRepository.deleteByServerId(serverId);
        serverRepository.delete(server);
    }

    /** 重新发现工具（动态热加载）。 */
    @RequiresScope("mcp:write")
    @Transactional
    public McpServerDto discover(String serverId) {
        McpServerEntity server = requireServer(serverId);
        syncTools(server);
        return McpServerDto.from(server, toolRepository.findByServerId(serverId),
                circuitBreaker.state(server.getName()).name().toLowerCase());
    }

    @RequiresScope("mcp:read")
    @Transactional
    public Map<String, Object> healthCheck(String serverId) {
        McpServerEntity server = requireServer(serverId);
        String[] health = gateway.healthCheck(server);
        server.setHealthStatus(health[0]);
        server.setLastError(health[1]);
        server.setLastHealthAt(Instant.now());
        serverRepository.save(server);

        return Map.of("server", server.getName(), "health_status", health[0], "error", health[1],
                "circuit", circuitBreaker.snapshot().getOrDefault(server.getName(),
                        Map.of("state", "closed", "samples", 0, "failure_rate", 0.0)));
    }

    @RequiresScope("mcp:write")
    public Map<String, Object> resetCircuit(String serverId) {
        McpServerEntity server = requireServer(serverId);
        circuitBreaker.reset(server.getName());
        return Map.of("server", server.getName(), "circuit_state", "closed");
    }

    @RequiresScope("mcp:read")
    public List<McpToolDto> listTools(String serverId) {
        List<McpToolEntity> tools = serverId == null || serverId.isBlank()
                ? toolRepository.findAll()
                : toolRepository.findByServerId(serverId);
        return tools.stream().map(McpToolDto::from).toList();
    }

    @RequiresScope("mcp:write")
    @Transactional
    public McpToolDto updateTool(String toolId, McpToolUpdateRequest request) {
        McpToolEntity tool = toolRepository.findById(toolId)
                .orElseThrow(() -> BizException.notFound("工具"));

        if (request.displayName() != null) tool.setDisplayName(request.displayName());
        if (request.description() != null) tool.setDescription(request.description());
        if (request.riskLevel() != null) tool.setRiskLevel(request.riskLevel());
        if (request.requiredScopes() != null) tool.setRequiredScopes(request.requiredScopes());
        if (request.readOnly() != null) tool.setReadOnly(request.readOnly());
        if (request.enabled() != null) tool.setEnabled(request.enabled());

        toolRepository.save(tool);
        return McpToolDto.from(tool);
    }

    /** 试调工具，用于接入验证。 */
    @RequiresScope("mcp:write")
    @Transactional
    public Map<String, Object> invokeTool(String toolId, Map<String, Object> arguments) {
        McpToolEntity tool = toolRepository.findById(toolId)
                .orElseThrow(() -> BizException.notFound("工具"));
        McpServerEntity server = requireServer(tool.getServerId());

        ToolResult result = gateway.call(server, tool.getName(),
                arguments == null ? Map.of() : arguments);

        tool.setCallCount(tool.getCallCount() + 1);
        if (result.success()) tool.setSuccessCount(tool.getSuccessCount() + 1);
        tool.setTotalLatencyMs(tool.getTotalLatencyMs() + result.latencyMs());
        toolRepository.save(tool);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.success());
        payload.put("data", result.data());
        payload.put("error_type", result.errorType());
        payload.put("error_message", result.errorMessage());
        payload.put("latency_ms", result.latencyMs());
        payload.put("attempts", result.attempts());
        payload.put("server", result.server());
        payload.put("tool", result.tool());
        return payload;
    }

    /**
     * 发现远端/内置工具并与本地注册表对齐。
     *
     * <p>描述与 schema 跟随上游更新；<b>风险等级与权限 scope 由平台侧治理，不被覆盖</b> ——
     * 否则上游改个描述就能把高风险工具降级成低风险，治理就失效了。
     */
    public void syncTools(McpServerEntity server) {
        List<ToolDescriptor> discovered;
        try {
            discovered = gateway.discover(server);
        } catch (Exception e) {
            server.setHealthStatus("down");
            server.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
            server.setLastHealthAt(Instant.now());
            serverRepository.save(server);
            throw new BizException(com.rsi.agent.common.ErrorCode.UPSTREAM_ERROR,
                    "工具发现失败：" + server.getLastError());
        }

        Map<String, McpToolEntity> existing = new LinkedHashMap<>();
        toolRepository.findByServerId(server.getId())
                .forEach(tool -> existing.put(tool.getName(), tool));

        List<String> seen = new ArrayList<>();
        for (ToolDescriptor descriptor : discovered) {
            seen.add(descriptor.name());
            McpToolEntity tool = existing.get(descriptor.name());

            if (tool == null) {
                tool = new McpToolEntity();
                tool.setServerId(server.getId());
                tool.setServerName(server.getName());
                tool.setName(descriptor.name());
                tool.setDisplayName(descriptor.name());
                tool.setRiskLevel(descriptor.risk());
                tool.setReadOnly(descriptor.readOnly());
                tool.setRequiredScopes(descriptor.scopes());
            }
            tool.setDescription(descriptor.description());
            tool.setInputSchema(descriptor.inputSchema());
            toolRepository.save(tool);
        }

        // 上游已下线的工具从注册表移除，避免出现「悬空」能力
        existing.values().stream()
                .filter(tool -> !seen.contains(tool.getName()))
                .forEach(toolRepository::delete);

        server.setHealthStatus("healthy");
        server.setLastError("");
        server.setLastHealthAt(Instant.now());
        serverRepository.save(server);
    }

    McpServerEntity requireServer(String serverId) {
        return serverRepository.findById(serverId)
                .orElseThrow(() -> BizException.notFound("MCP Server"));
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
