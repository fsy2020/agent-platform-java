package com.rsi.agent.config;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.rsi.agent.annotation.CurrentUser;
import com.rsi.agent.security.Identity;
import com.rsi.agent.security.IdentityContext;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配。
 *
 * <p>三件事都属于「一次配置、处处生效」的横切关注点：
 * <ul>
 *   <li>Jackson 全局 snake_case —— Java 侧保持 camelCase，出网自动转换</li>
 *   <li>{@code @CurrentUser} 参数解析器 —— 控制器直接拿到身份对象</li>
 *   <li>CORS —— 前端独立端口开发时需要</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 命名转换是序列化层的职责，不该让 Java 字段去迁就 JSON 约定。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer snakeCaseCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 把 {@link Identity} 注入控制器形参。
     *
     * <p>省掉每个方法开头「取 Header → 校验 Token → 查库 → 判空」的四行样板，
     * 也保证了「没有身份就 401」这条规则只实现一次。
     */
    static class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentUser.class)
                    && Identity.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                      NativeWebRequest request, WebDataBinderFactory factory) {
            return IdentityContext.require();
        }
    }
}
