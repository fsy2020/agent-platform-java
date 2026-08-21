package com.rsi.agent.mcp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rsi.agent.annotation.McpTool;
import com.rsi.agent.annotation.McpToolProvider;
import com.rsi.agent.common.BizException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

/**
 * 反射核心（其二）：启动时扫描所有 {@code @McpToolProvider} Bean，
 * 把带 {@code @McpTool} 的方法注册成可调用的工具，并在运行时按参数名动态绑定调用。
 *
 * <p>IoC 与反射在这里合流：Bean 由容器给（含依赖注入），方法由反射找，
 * 入参由 JSON Map 按名绑定。业务方新增一个方法即上线一个 MCP 工具，
 * 无需改注册表、无需写 schema、无需碰网关。
 */
@Component
public class BuiltinToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolRegistry.class);

    /** 从容器取 Bean 而不是构造注入 List，是为了同时拿到 Bean 上的 @McpToolProvider 元数据。 */
    private final ApplicationContext context;

    /** serverName -> (toolName -> handle) */
    private final Map<String, Map<String, Handle>> registry = new LinkedHashMap<>();

    /** serverName -> Server 级元数据 */
    private final Map<String, ServerMeta> servers = new LinkedHashMap<>();

    public BuiltinToolRegistry(ApplicationContext context) {
        this.context = context;
    }

    /** 一个可调用的工具：描述符 + 目标 Bean + 目标方法。 */
    public record Handle(ToolDescriptor descriptor, Object bean, Method method) {}

    public record ServerMeta(String name, String displayName, String description, String domain) {}

    @PostConstruct
    void scan() {
        for (Object bean : context.getBeansWithAnnotation(McpToolProvider.class).values()) {
            // 目标可能被 AOP 代理包装，注解要从原始类上找
            Class<?> targetClass = org.springframework.aop.support.AopUtils.getTargetClass(bean);
            McpToolProvider provider = AnnotationUtils.findAnnotation(targetClass, McpToolProvider.class);
            if (provider == null) continue;

            Map<String, Handle> tools = new LinkedHashMap<>();
            for (Method method : targetClass.getDeclaredMethods()) {
                McpTool annotation = AnnotationUtils.findAnnotation(method, McpTool.class);
                if (annotation == null) continue;
                method.setAccessible(true);
                ToolDescriptor descriptor = ToolSchemaFactory.describe(provider.name(), bean, method);
                tools.put(descriptor.name(), new Handle(descriptor, bean, method));
            }

            servers.put(provider.name(), new ServerMeta(provider.name(),
                    provider.displayName(), provider.description(), provider.domain()));
            registry.put(provider.name(), tools);
            log.info("内置 MCP Server [{}] 反射注册 {} 个工具", provider.name(), tools.size());
        }
    }

    public List<ServerMeta> servers() {
        return List.copyOf(servers.values());
    }

    public ServerMeta serverMeta(String server) {
        return servers.get(server);
    }

    public boolean hasServer(String server) {
        return registry.containsKey(server);
    }

    public List<ToolDescriptor> descriptors(String server) {
        Map<String, Handle> tools = registry.get(server);
        if (tools == null) throw BizException.badRequest("内置 Server 不存在：" + server);
        return tools.values().stream().map(Handle::descriptor).toList();
    }

    /**
     * 按名动态调用。JSON 入参（snake_case）按形参名绑定并做类型强转，
     * 缺失的可选参数按 Java 默认值补齐。
     */
    public Object invoke(String server, String toolName, Map<String, Object> args) throws Exception {
        Map<String, Handle> tools = registry.get(server);
        if (tools == null) throw BizException.badRequest("内置 Server 不存在：" + server);
        Handle handle = tools.get(toolName);
        if (handle == null) throw BizException.notFound("工具 " + server + "." + toolName);

        Object[] bound = bindArguments(handle.method(), args == null ? Map.of() : args);
        try {
            return handle.method().invoke(handle.bean(), bound);
        } catch (InvocationTargetException e) {
            // 解包反射异常，让上层看到真实的业务异常
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new IllegalStateException(cause);
        }
    }

    /** 按形参名从 JSON Map 取值并转型 —— 反射调用的关键一步。 */
    private Object[] bindArguments(Method method, Map<String, Object> args) {
        Parameter[] parameters = method.getParameters();
        List<Object> values = new ArrayList<>(parameters.length);

        for (Parameter parameter : parameters) {
            String key = ToolSchemaFactory.toSnakeCase(parameter.getName());
            Object raw = args.get(key);
            values.add(coerce(raw, parameter.getType()));
        }
        return values.toArray();
    }

    /** 宽松类型转换：模型给的 JSON 值类型经常与 Java 形参不完全一致。 */
    private Object coerce(Object raw, Class<?> type) {
        if (raw == null) return defaultValue(type);

        if (type == String.class) return String.valueOf(raw);
        if (type == int.class || type == Integer.class) return toNumber(raw).intValue();
        if (type == long.class || type == Long.class) return toNumber(raw).longValue();
        if (type == double.class || type == Double.class) return toNumber(raw).doubleValue();
        if (type == boolean.class || type == Boolean.class) {
            return raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
        }
        return raw;
    }

    private Number toNumber(Object raw) {
        if (raw instanceof Number n) return n;
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return 0;
        return new BigDecimal(text);
    }

    private Object defaultValue(Class<?> type) {
        if (type == String.class) return "";
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == double.class || type == Double.class) return 0d;
        if (type == boolean.class || type == Boolean.class) return false;
        return null;
    }
}
