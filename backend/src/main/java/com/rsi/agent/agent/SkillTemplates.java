package com.rsi.agent.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Skill 步骤间的数据流转。
 *
 * <p>步骤依赖用引用语法<b>显式声明</b>：{@code $input.employee_id}、
 * {@code $steps.search.data.customers.0.id}。这让一个 Skill 本质上是一张
 * 声明式依赖图 —— 当前按注册顺序线性执行，但依赖关系是可读、可校验的，
 * 将来要并行只需按 depends_on 做拓扑排序。
 */
public final class SkillTemplates {

    private SkillTemplates() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*\\$([\\w.]+)\\s*}}");

    /** 解析步骤入参：字面量 args + input_map 引用。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolveStepArgs(Map<String, Object> step, Map<String, Object> scope) {
        Map<String, Object> args = new LinkedHashMap<>();

        Object rawArgs = step.get("args");
        if (rawArgs instanceof Map<?, ?> map) {
            map.forEach((key, value) -> args.put(String.valueOf(key), value));
        }

        Object inputMap = step.get("input_map");
        if (inputMap instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object resolved = resolveRef(String.valueOf(entry.getValue()), scope);
                if (resolved != null) args.put(String.valueOf(entry.getKey()), resolved);
            }
        }
        return args;
    }

    /** 解析 {@code $a.b.0.c} 形式的引用；支持穿透 Map 与 List 下标。 */
    public static Object resolveRef(String ref, Map<String, Object> scope) {
        if (ref == null || !ref.startsWith("$")) return ref;

        Object cursor = scope;
        for (String part : ref.substring(1).split("\\.")) {
            if (cursor instanceof Map<?, ?> map) {
                cursor = map.get(part);
            } else if (cursor instanceof List<?> list && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                cursor = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
            if (cursor == null) return null;
        }
        return cursor;
    }

    /** 渲染输出模板，把 {@code {{$steps.x.data.y}}} 替换成实际值。 */
    public static String render(String template, Map<String, Object> scope) {
        if (template == null || template.isBlank()) return "";

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = resolveRef("$" + matcher.group(1), scope);
            String replacement;
            if (value == null) {
                replacement = "";
            } else if (value instanceof String text) {
                replacement = text;
            } else {
                try {
                    replacement = MAPPER.writeValueAsString(value);
                } catch (Exception e) {
                    replacement = String.valueOf(value);
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
