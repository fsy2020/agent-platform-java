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

/**
 * HR 人力系统。
 *
 * <p>注意这个类里没有任何 MCP 协议代码 —— 业务方写的就是普通 Java 方法，
 * 工具名、JSON Schema、必填项、风险等级、权限 scope 全部由注解声明、反射生成。
 * 接入一个新 API 的成本 = 写一个方法 + 一行注解。
 */
@McpToolProvider(name = "hr", displayName = "HR 人力系统",
        description = "员工档案、组织架构、假期额度与请假申请。", domain = "hr")
public class HrToolProvider {

    private static final Map<String, Map<String, Object>> EMPLOYEES = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> LEAVE = new LinkedHashMap<>();
    private static final AtomicInteger LEAVE_SEQ = new AtomicInteger(700);

    static {
        EMPLOYEES.put("E1001", employee("E1001", "范思远", "AI大模型事业部",
                "AI Agent 研发工程师", "E1000", "fsy@example.com", "东京", "2024-04-01"));
        EMPLOYEES.put("E1002", employee("E1002", "李珂", "AI大模型事业部",
                "后端工程师", "E1000", "like@example.com", "苏州", "2023-09-01"));
        EMPLOYEES.put("E1000", employee("E1000", "王敏", "AI大模型事业部",
                "技术总监", "", "wangmin@example.com", "东京", "2020-02-01"));

        LEAVE.put("E1001", leave(12.5, 10, 3.5));
        LEAVE.put("E1002", leave(8.0, 10, 7.0));
        LEAVE.put("E1000", leave(15.0, 10, 2.0));
    }

    private static Map<String, Object> employee(String id, String name, String dept, String title,
                                                String manager, String email, String location,
                                                String hireDate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("dept", dept);
        map.put("title", title);
        map.put("manager", manager);
        map.put("email", email);
        map.put("location", location);
        map.put("hire_date", hireDate);
        return map;
    }

    private static Map<String, Object> leave(double annual, int sick, double usedAnnual) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("annual", annual);
        map.put("sick", sick);
        map.put("used_annual", usedAnnual);
        return map;
    }

    @McpTool(description = "按工号或姓名查询员工基本信息（部门、职位、上级、邮箱）。",
            scopes = "hr:read")
    public Map<String, Object> getEmployee(
            @ToolParam("员工工号，如 E1001") String employeeId,
            @ToolParam("员工姓名") String name) {
        if (!employeeId.isBlank() && EMPLOYEES.containsKey(employeeId)) {
            return EMPLOYEES.get(employeeId);
        }
        if (!name.isBlank()) {
            for (Map<String, Object> employee : EMPLOYEES.values()) {
                if (String.valueOf(employee.get("name")).contains(name)) return employee;
            }
        }
        return Map.of("error", "employee_not_found",
                "message", "未找到员工（id=" + (employeeId.isBlank() ? "-" : employeeId)
                        + ", name=" + (name.isBlank() ? "-" : name) + "）");
    }

    @McpTool(description = "查询员工年假/病假剩余额度。", scopes = "hr:read")
    public Map<String, Object> getLeaveBalance(
            @ToolParam(value = "员工工号", required = true) String employeeId) {
        Map<String, Object> balance = LEAVE.get(employeeId);
        if (balance == null) {
            return Map.of("error", "employee_not_found", "message", "员工 " + employeeId + " 不存在");
        }
        double remaining = (double) balance.get("annual") - (double) balance.get("used_annual");
        Map<String, Object> result = new LinkedHashMap<>(balance);
        result.put("employee_id", employeeId);
        result.put("remaining_annual", Math.round(remaining * 10) / 10.0);
        return result;
    }

    @McpTool(description = "列出某部门的成员清单。", scopes = "hr:read")
    public Map<String, Object> listDepartment(@ToolParam("部门名称，留空返回全部") String dept) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> employee : EMPLOYEES.values()) {
            if (dept.isBlank() || String.valueOf(employee.get("dept")).contains(dept)) {
                members.add(employee);
            }
        }
        return Map.of("department", dept.isBlank() ? "全部" : dept,
                "count", members.size(), "members", members);
    }

    @McpTool(description = "提交请假申请（写操作，会进入主管审批流）。",
            risk = "high", readOnly = false, scopes = "hr:write")
    public Map<String, Object> submitLeaveRequest(
            @ToolParam(value = "员工工号", required = true) String employeeId,
            @ToolParam(value = "开始日期 YYYY-MM-DD", required = true) String startDate,
            @ToolParam(value = "结束日期 YYYY-MM-DD", required = true) String endDate,
            @ToolParam("假期类型 annual/sick") String leaveType,
            @ToolParam("请假事由") String reason) {
        Map<String, Object> employee = EMPLOYEES.get(employeeId);
        if (employee == null) {
            return Map.of("error", "employee_not_found", "message", "员工 " + employeeId + " 不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request_id", "LV-" + LocalDate.now().getYear() + "-" + LEAVE_SEQ.incrementAndGet());
        result.put("employee_id", employeeId);
        result.put("leave_type", leaveType.isBlank() ? "annual" : leaveType);
        result.put("start_date", startDate);
        result.put("end_date", endDate);
        result.put("reason", reason);
        result.put("status", "已提交待主管审批");
        result.put("approver", employee.get("manager"));
        return result;
    }
}
