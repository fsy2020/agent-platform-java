package com.rsi.agent;

import com.rsi.agent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 企业级多 Agent 运行中台。
 *
 * <p>架构：Supervisor（主 Agent） + 子 Agent/Skill Registry + MCP Gateway。
 * 三条主线分别由 Spring 的三项能力支撑：
 * <ul>
 *   <li><b>IoC</b>：传输策略、图节点、工具提供者全部由容器装配，新增实现零改动</li>
 *   <li><b>AOP</b>：权限校验、链路观测、重试熔断以切面横切，业务代码不感知</li>
 *   <li><b>反射</b>：Java 方法 + 注解自动生成 MCP 工具 schema 并动态调用</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
@EnableAsync
public class AgentPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
