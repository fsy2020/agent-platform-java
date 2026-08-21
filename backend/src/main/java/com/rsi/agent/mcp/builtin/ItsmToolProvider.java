package com.rsi.agent.mcp.builtin;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.rsi.agent.annotation.McpTool;
import com.rsi.agent.annotation.McpToolProvider;
import com.rsi.agent.annotation.ToolParam;

/** ITSM 服务台：知识库检索、工单查询与创建、账号密码重置。 */
@McpToolProvider(name = "itsm", displayName = "ITSM 服务台",
        description = "IT 知识库检索、工单查询与创建、账号密码重置。", domain = "it")
public class ItsmToolProvider {

    private static final Map<String, Map<String, Object>> TICKETS = new LinkedHashMap<>();
    private static final List<Map<String, Object>> KB = new ArrayList<>();
    private static final AtomicInteger TICKET_SEQ = new AtomicInteger(1000);

    static {
        TICKETS.put("INC-20260801", ticket("INC-20260801", "VPN 无法连接", "处理中",
                "P2", "网络组", "E1001", "2026-08-01", "网络"));
        TICKETS.put("INC-20260725", ticket("INC-20260725", "邮箱容量不足", "已解决",
                "P3", "协同组", "E1002", "2026-07-25", "办公"));

        KB.add(article("KB-001", "VPN 客户端安装与常见故障排查", "网络",
                "覆盖 Windows/macOS 安装步骤、证书过期、双因子认证失败三类高频问题。",
                "1. 确认已安装最新版客户端；2. 证书过期请到自助门户重新签发；3. 双因子失败时检查手机时间同步。"));
        KB.add(article("KB-014", "报销标准与发票要求", "财务",
                "差旅、餐饮、办公用品的额度标准与发票合规要求。",
                "国内差旅住宿一线城市上限 600 元/晚；餐饮 150 元/人/天；需提供增值税普通发票及行程单。"));
        KB.add(article("KB-022", "内部 API 接入 MCP 工具规范", "研发",
                "OpenAPI schema 转 MCP tool 的参数命名、错误码归一与鉴权透传约定。",
                "参数使用 snake_case；错误统一为 {code, message, retryable}；鉴权由网关注入 Bearer Token。"));
        KB.add(article("KB-031", "年假政策说明", "人事",
                "年假额度计算、结转规则与申请流程。",
                "入职满一年起每年 10 天，每满三年加 1 天，上限 15 天；当年未休可结转至次年 3 月 31 日。"));
    }

    private static Map<String, Object> ticket(String id, String title, String status, String priority,
                                              String assignee, String reporter, String createdAt,
                                              String category) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("status", status);
        map.put("priority", priority);
        map.put("assignee", assignee);
        map.put("reporter", reporter);
        map.put("created_at", createdAt);
        map.put("category", category);
        return map;
    }

    private static Map<String, Object> article(String id, String title, String category,
                                               String summary, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("category", category);
        map.put("summary", summary);
        map.put("content", content);
        return map;
    }

    @McpTool(description = "检索 IT 知识库文章，用于自助排障与政策查询。", scopes = "it:read")
    public Map<String, Object> searchKb(
            @ToolParam(value = "检索关键词", required = true) String query,
            @ToolParam("分类：网络/办公/研发/财务/人事") String category,
            @ToolParam("返回条数") int limit) {
        int size = limit <= 0 ? 3 : limit;
        List<Map<String, Object>> hits = new ArrayList<>();

        for (Map<String, Object> art : KB) {
            if (!category.isBlank() && !category.equals(art.get("category"))) continue;
            String haystack = art.get("title") + "" + art.get("summary") + art.get("content");
            long score = query.chars().filter(c -> haystack.indexOf(c) >= 0).count();
            if (score > 0) hits.add(art);
        }
        return Map.of("query", query, "total", hits.size(),
                "results", hits.subList(0, Math.min(size, hits.size())));
    }

    @McpTool(description = "按工单号查询工单详情与处理状态。", scopes = "it:read")
    public Map<String, Object> getTicket(
            @ToolParam(value = "工单号，如 INC-20260801", required = true) String ticketId) {
        Map<String, Object> ticket = TICKETS.get(ticketId);
        return ticket != null ? ticket
                : Map.of("error", "ticket_not_found", "message", "工单 " + ticketId + " 不存在");
    }

    @McpTool(description = "按报障人或状态列出工单。", scopes = "it:read")
    public Map<String, Object> listTickets(@ToolParam("报障人工号") String reporter,
                                           @ToolParam("状态") String status) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> ticket : TICKETS.values()) {
            boolean matchReporter = reporter.isBlank() || reporter.equals(ticket.get("reporter"));
            boolean matchStatus = status.isBlank() || status.equals(ticket.get("status"));
            if (matchReporter && matchStatus) items.add(ticket);
        }
        return Map.of("count", items.size(), "tickets", items);
    }

    @McpTool(description = "创建 IT 工单（写操作）。", risk = "medium", readOnly = false,
            scopes = "it:write")
    public Map<String, Object> createTicket(
            @ToolParam(value = "工单标题", required = true) String title,
            @ToolParam("问题描述") String description,
            @ToolParam("优先级 P1-P4") String priority,
            @ToolParam("分类") String category,
            @ToolParam("报障人工号") String reporter) {
        String id = "INC-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"))
                + TICKET_SEQ.incrementAndGet();
        Map<String, Object> created = ticket(id, title, "待受理",
                priority.isBlank() ? "P3" : priority, "服务台", reporter,
                LocalDate.now().toString(), category.isBlank() ? "其他" : category);
        created.put("description", description);
        TICKETS.put(id, created);
        return created;
    }

    @McpTool(description = "更新工单状态/处理人（写操作）。", risk = "high", readOnly = false,
            scopes = "it:write")
    public Map<String, Object> updateTicket(
            @ToolParam(value = "工单号", required = true) String ticketId,
            @ToolParam("新状态") String status,
            @ToolParam("处理人") String assignee,
            @ToolParam("处理备注") String comment) {
        Map<String, Object> ticket = TICKETS.get(ticketId);
        if (ticket == null) {
            return Map.of("error", "ticket_not_found", "message", "工单 " + ticketId + " 不存在");
        }
        if (!status.isBlank()) ticket.put("status", status);
        if (!assignee.isBlank()) ticket.put("assignee", assignee);
        if (!comment.isBlank()) ticket.put("last_comment", comment);
        return ticket;
    }

    @McpTool(description = "重置员工域账号密码（高风险，执行前需人工审批）。",
            risk = "high", readOnly = false, scopes = "it:admin")
    public Map<String, Object> resetPassword(
            @ToolParam(value = "员工工号", required = true) String employeeId,
            @ToolParam("目标系统，默认 AD") String system) {
        Map<String, String> mailbox = Map.of(
                "E1001", "fsy@example.com", "E1002", "like@example.com",
                "E1000", "wangmin@example.com");
        if (!mailbox.containsKey(employeeId)) {
            return Map.of("error", "employee_not_found", "message", "员工 " + employeeId + " 不存在");
        }
        return Map.of("employee_id", employeeId,
                "system", system.isBlank() ? "AD" : system,
                "status", "已重置",
                "temp_password_sent_to", mailbox.get(employeeId),
                "expires_in_minutes", 30);
    }
}
