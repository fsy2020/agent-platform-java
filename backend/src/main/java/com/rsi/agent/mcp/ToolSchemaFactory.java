package com.rsi.agent.mcp;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rsi.agent.annotation.McpTool;
import com.rsi.agent.annotation.ToolParam;

/**
 * 反射核心（其一）：把 Java 方法签名翻译成 MCP 的 JSON Schema。
 *
 * <p>这是「150+ 内部 REST API 标准化封装为 MCP 工具」的落地点。接入方只写业务方法：
 * <pre>{@code
 * @McpTool(description = "按工号查询员工", scopes = "hr:read")
 * public Map<String,Object> getEmployee(@ToolParam(value="员工工号", required=true) String employeeId) {...}
 * }</pre>
 * 平台反射读取形参名、类型与注解，自动产出：
 * <pre>{@code
 * {"type":"object",
 *  "properties":{"employee_id":{"type":"string","description":"员工工号"}},
 *  "required":["employee_id"]}
 * }</pre>
 * 参数名从 camelCase 转 snake_case —— MCP 生态的通行约定，也与既有前端契约一致。
 */
public final class ToolSchemaFactory {

    private ToolSchemaFactory() {}

    /** 反射生成入参 schema。依赖编译期 -parameters 保留真实形参名。 */
    public static Map<String, Object> buildInputSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter parameter : method.getParameters()) {
            String jsonName = toSnakeCase(parameter.getName());
            ToolParam meta = parameter.getAnnotation(ToolParam.class);

            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", jsonType(parameter.getType()));
            if (meta != null && !meta.value().isBlank()) {
                spec.put("description", meta.value());
            }
            properties.put(jsonName, spec);

            if (meta != null && meta.required()) {
                required.add(jsonName);
            }
        }

        return Map.of("type", "object", "properties", properties, "required", required);
    }

    /** 从注解 + 方法名派生工具描述符。 */
    public static ToolDescriptor describe(String server, Object bean, Method method) {
        McpTool annotation = method.getAnnotation(McpTool.class);
        String name = annotation.value().isBlank() ? toSnakeCase(method.getName()) : annotation.value();
        return new ToolDescriptor(
                server,
                name,
                annotation.description(),
                buildInputSchema(method),
                annotation.risk(),
                annotation.readOnly(),
                List.of(annotation.scopes())
        );
    }

    /** Java 类型 → JSON Schema 类型。 */
    private static String jsonType(Class<?> type) {
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "array";
        }
        if (Map.class.isAssignableFrom(type)) {
            return "object";
        }
        return "string";
    }

    /** getEmployeeId → get_employee_id；employeeId → employee_id。 */
    public static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 8);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
