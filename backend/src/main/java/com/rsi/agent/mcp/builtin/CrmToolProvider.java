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

/** CRM 客户系统：客户检索、订单查询、跟进任务。 */
@McpToolProvider(name = "crm", displayName = "CRM 客户系统",
        description = "客户档案检索、订单查询与跟进任务创建。", domain = "sales")
public class CrmToolProvider {

    private static final Map<String, Map<String, Object>> CUSTOMERS = new LinkedHashMap<>();
    private static final Map<String, List<Map<String, Object>>> ORDERS = new LinkedHashMap<>();
    private static final AtomicInteger FOLLOWUP_SEQ = new AtomicInteger(300);

    static {
        CUSTOMERS.put("C-8801", customer("C-8801", "东京精密制造", "A", "E1002",
                "制造", 1200000, "2026-11-30"));
        CUSTOMERS.put("C-8802", customer("C-8802", "关西物流集团", "B", "E1002",
                "物流", 460000, "2027-02-28"));

        ORDERS.put("C-8801", List.of(
                order("SO-2026-0231", 380000, "已交付", "2026-03-11"),
                order("SO-2026-0418", 820000, "生产中", "2026-06-02")));
        ORDERS.put("C-8802", List.of(
                order("SO-2026-0377", 460000, "已交付", "2026-05-19")));
    }

    private static Map<String, Object> customer(String id, String name, String level, String owner,
                                                String industry, int arr, String renewDate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("level", level);
        map.put("owner", owner);
        map.put("industry", industry);
        map.put("arr", arr);
        map.put("renew_date", renewDate);
        return map;
    }

    private static Map<String, Object> order(String id, int amount, String status, String date) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("order_id", id);
        map.put("amount", amount);
        map.put("status", status);
        map.put("date", date);
        return map;
    }

    @McpTool(description = "按名称或等级检索客户。", scopes = "crm:read")
    public Map<String, Object> searchCustomer(@ToolParam("客户名称关键词") String keyword,
                                              @ToolParam("客户等级 A/B/C") String level) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> customer : CUSTOMERS.values()) {
            String name = String.valueOf(customer.get("name"));
            boolean matchLevel = level.isBlank() || level.equals(customer.get("level"));
            if (fuzzyHit(keyword, name) && matchLevel) items.add(customer);
        }
        return Map.of("count", items.size(), "customers", items);
    }

    /** 模糊匹配：整串命中，或关键词中任意 2 字片段命中 —— 贴近真实检索 API 行为。 */
    private boolean fuzzyHit(String keyword, String name) {
        if (keyword.isBlank()) return true;
        if (name.contains(keyword)) return true;
        for (int i = 0; i + 2 <= keyword.length(); i++) {
            if (name.contains(keyword.substring(i, i + 2))) return true;
        }
        return false;
    }

    @McpTool(description = "查询客户历史订单与总金额。", scopes = "crm:read")
    public Map<String, Object> getOrders(
            @ToolParam(value = "客户编号，如 C-8801", required = true) String customerId) {
        Map<String, Object> customer = CUSTOMERS.get(customerId);
        if (customer == null) {
            return Map.of("error", "customer_not_found", "message", "客户 " + customerId + " 不存在");
        }
        List<Map<String, Object>> orders = ORDERS.getOrDefault(customerId, List.of());
        long total = orders.stream().mapToLong(o -> ((Number) o.get("amount")).longValue()).sum();
        return Map.of("customer_id", customerId,
                "customer_name", customer.get("name"),
                "count", orders.size(),
                "total_amount", total,
                "orders", orders);
    }

    @McpTool(description = "创建客户跟进任务（写操作）。", risk = "medium", readOnly = false,
            scopes = "crm:write")
    public Map<String, Object> createFollowup(
            @ToolParam(value = "客户编号", required = true) String customerId,
            @ToolParam(value = "跟进内容", required = true) String content,
            @ToolParam("截止日期 YYYY-MM-DD") String dueDate) {
        Map<String, Object> customer = CUSTOMERS.get(customerId);
        if (customer == null) {
            return Map.of("error", "customer_not_found", "message", "客户 " + customerId + " 不存在");
        }
        return Map.of("followup_id", "FU-" + FOLLOWUP_SEQ.incrementAndGet(),
                "customer_id", customerId,
                "content", content,
                "due_date", dueDate.isBlank() ? LocalDate.now().plusDays(7).toString() : dueDate,
                "owner", customer.get("owner"),
                "status", "待跟进");
    }
}
