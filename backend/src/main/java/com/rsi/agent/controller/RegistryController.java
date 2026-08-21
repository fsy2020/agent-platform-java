package com.rsi.agent.controller;

import java.util.List;
import java.util.Map;

import com.rsi.agent.annotation.CurrentUser;
import com.rsi.agent.dto.RegistryDtos.*;
import com.rsi.agent.security.Identity;
import com.rsi.agent.service.McpRegistryService;
import com.rsi.agent.service.SkillService;
import com.rsi.agent.service.SubAgentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 注册中心 API：MCP Server / 工具 / Skill / 子 Agent。
 *
 * <p>控制器只做协议转换 —— 参数绑定、调用服务、返回 DTO。
 * 权限校验在服务层由 {@code @RequiresScope} 切面完成，异常由全局处理器兜底，
 * 因此这里既没有 if-role，也没有 try-catch。
 */
@RestController
@RequestMapping("/api")
public class RegistryController {

    private final McpRegistryService mcpService;
    private final SkillService skillService;
    private final SubAgentService agentService;

    public RegistryController(McpRegistryService mcpService, SkillService skillService,
                              SubAgentService agentService) {
        this.mcpService = mcpService;
        this.skillService = skillService;
        this.agentService = agentService;
    }

    // ---------------------------------------------------------------- MCP

    @GetMapping("/mcp/builtin-catalog")
    public List<Map<String, Object>> builtinCatalog() {
        return mcpService.builtinCatalog();
    }

    @GetMapping("/mcp/servers")
    public List<McpServerDto> listServers() {
        return mcpService.listServers();
    }

    @PostMapping("/mcp/servers")
    @ResponseStatus(HttpStatus.CREATED)
    public McpServerDto createServer(@Valid @RequestBody McpServerCreateRequest request,
                                     @CurrentUser Identity identity) {
        return mcpService.createServer(request, identity);
    }

    @PatchMapping("/mcp/servers/{serverId}")
    public McpServerDto updateServer(@PathVariable String serverId,
                                     @RequestBody McpServerUpdateRequest request) {
        return mcpService.updateServer(serverId, request);
    }

    @DeleteMapping("/mcp/servers/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteServer(@PathVariable String serverId) {
        mcpService.deleteServer(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/discover")
    public McpServerDto discover(@PathVariable String serverId) {
        return mcpService.discover(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/health")
    public Map<String, Object> health(@PathVariable String serverId) {
        return mcpService.healthCheck(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/reset-circuit")
    public Map<String, Object> resetCircuit(@PathVariable String serverId) {
        return mcpService.resetCircuit(serverId);
    }

    @GetMapping("/mcp/tools")
    public List<McpToolDto> listTools(@RequestParam(name = "server_id", required = false) String serverId) {
        return mcpService.listTools(serverId);
    }

    @PatchMapping("/mcp/tools/{toolId}")
    public McpToolDto updateTool(@PathVariable String toolId,
                                 @RequestBody McpToolUpdateRequest request) {
        return mcpService.updateTool(toolId, request);
    }

    @PostMapping("/mcp/tools/{toolId}/invoke")
    public Map<String, Object> invokeTool(@PathVariable String toolId,
                                         @RequestBody ToolInvokeRequest request) {
        return mcpService.invokeTool(toolId, request.arguments());
    }

    // ---------------------------------------------------------------- Skill

    @GetMapping("/skills")
    public List<SkillDto> listSkills() {
        return skillService.list();
    }

    @PostMapping("/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillDto createSkill(@Valid @RequestBody SkillSaveRequest request,
                                @CurrentUser Identity identity) {
        return skillService.create(request, identity);
    }

    @GetMapping("/skills/{skillId}")
    public SkillDto getSkill(@PathVariable String skillId) {
        return skillService.get(skillId);
    }

    @PatchMapping("/skills/{skillId}")
    public SkillDto updateSkill(@PathVariable String skillId, @RequestBody SkillSaveRequest request,
                                @CurrentUser Identity identity) {
        return skillService.update(skillId, request, identity);
    }

    @DeleteMapping("/skills/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(@PathVariable String skillId) {
        skillService.delete(skillId);
    }

    @PostMapping("/skills/{skillId}/dry-run")
    public Map<String, Object> dryRun(@PathVariable String skillId,
                                      @RequestBody Map<String, Object> inputs,
                                      @CurrentUser Identity identity) {
        return skillService.dryRun(skillId, inputs, identity);
    }

    // ---------------------------------------------------------------- 子 Agent

    @GetMapping("/agents")
    public List<SubAgentDto> listAgents() {
        return agentService.list();
    }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public SubAgentDto createAgent(@RequestBody SubAgentSaveRequest request,
                                   @CurrentUser Identity identity) {
        return agentService.create(request, identity);
    }

    @GetMapping("/agents/{agentId}")
    public SubAgentDto getAgent(@PathVariable String agentId) {
        return agentService.get(agentId);
    }

    @PatchMapping("/agents/{agentId}")
    public SubAgentDto updateAgent(@PathVariable String agentId,
                                   @RequestBody SubAgentSaveRequest request,
                                   @CurrentUser Identity identity) {
        return agentService.update(agentId, request, identity);
    }

    @DeleteMapping("/agents/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgent(@PathVariable String agentId) {
        agentService.delete(agentId);
    }

    @GetMapping("/agents/{agentId}/effective-capabilities")
    public Map<String, Object> effectiveCapabilities(@PathVariable String agentId,
                                                     @CurrentUser Identity identity) {
        return agentService.effectiveCapabilities(agentId, identity);
    }
}
