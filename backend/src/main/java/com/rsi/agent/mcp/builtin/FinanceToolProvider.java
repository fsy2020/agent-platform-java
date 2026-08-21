package com.rsi.agent.mcp.builtin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.rsi.agent.annotation.McpTool;
import com.rsi.agent.annotation.McpToolProvider;
import com.rsi.agent.annotation.ToolParam;

/** 财务报销系统：政策查询、报销单查询与提交。 */
@McpToolProvider(name = "finance", displayName = "财务报销系统",
        description = "报销政策查询、报销单状态查询与提交。", domain = "finance")
public class FinanceToolProvider {

    private static final Map<String, Map<String, Object>> REIMBURSEMENTS = new LinkedHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger(5000);

    private static final Map<String, Object> POLICIES = Map.of(
            "差旅", Map.of("住宿", "一线城市 600 元/晚，其他 400 元/晚",
                    "交通", "国内经济舱/高铁二等座"),
            "餐饮", Map.of("标准", "150 元/人/天", "备注", "需提供参与人名单"),
            "办公用品", Map.of("标准", "单次 2000 元以内经理审批，以上需总监审批"));

    static {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("id", "RB-2026-0912");
        sample.put("employee", "E1001");
        sample.put("amount", 2380.5);
        sample.put("status", "审批中");
        sample.put("type", "差旅");
        sample.put("submit_date", "2026-08-05");
        REIMBURSEMENTS.put("RB-2026-0912", sample);
    }

    @McpTool(description = "查询报销标准与发票要求。", scopes = "fin:read")
    public Map<String, Object> getPolicy(
            @ToolParam("费用类型：差旅/餐饮/办公用品") String expenseType) {
        if (!expenseType.isBlank() && POLICIES.containsKey(expenseType)) {
            return Map.of("expense_type", expenseType, "policy", POLICIES.get(expenseType));
        }
        return Map.of("policies", POLICIES);
    }

    @McpTool(description = "查询报销单状态。", scopes = "fin:read")
    public Map<String, Object> getReimbursement(@ToolParam("报销单号") String requestId,
                                                @ToolParam("员工工号") String employeeId) {
        if (!requestId.isBlank()) {
            Map<String, Object> item = REIMBURSEMENTS.get(requestId);
            return item != null ? item
                    : Map.of("error", "not_found", "message", "报销单 " + requestId + " 不存在");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> item : REIMBURSEMENTS.values()) {
            if (employeeId.isBlank() || employeeId.equals(item.get("employee"))) items.add(item);
        }
        return Map.of("count", items.size(), "items", items);
    }

    @McpTool(description = "提交报销单（写操作，涉及资金）。", risk = "high", readOnly = false,
            scopes = "fin:write")
    public Map<String, Object> submitReimbursement(
            @ToolParam(value = "员工工号", required = true) String employeeId,
            @ToolParam(value = "金额", required = true) double amount,
            @ToolParam(value = "费用类型", required = true) String expenseType,
            @ToolParam("费用说明") String description,
            @ToolParam("发票号") String invoiceNo) {
        String id = "RB-" + LocalDate.now().getYear() + "-" + SEQ.incrementAndGet();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("employee", employeeId);
        record.put("amount", amount);
        record.put("status", "审批中");
        record.put("type", expenseType);
        record.put("submit_date", LocalDate.now().toString());
        record.put("description", description);
        record.put("invoice_no", invoiceNo);
        REIMBURSEMENTS.put(id, record);
        return record;
    }
}
