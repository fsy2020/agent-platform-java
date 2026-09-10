package com.rsi.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 类型安全的配置绑定（record + @ConfigurationProperties）。
 * 相比散落各处的 @Value，这里一次性声明全部可调参数，并带默认值。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        Jwt jwt,
        Llm llm,
        Runtime runtime,
        Resilience resilience,
        A2a a2a
) {
    /** a2a 段可整体缺省 —— 不接远程子 Agent 的部署不必写这段配置。 */
    public AgentProperties {
        if (a2a == null) a2a = new A2a(0, 0, 0, 0L, 0, null);
    }

    public record Jwt(String secret, long expireMinutes) {
        public Jwt {
            if (secret == null || secret.isBlank()) {
                secret = "rsi-agent-platform-dev-secret-please-change-in-production-0123456789";
            }
            if (expireMinutes <= 0) expireMinutes = 720;
        }
    }

    public record Llm(String apiKey, String primaryModel, String fallbackModel,
                      String effort, int maxTokens) {
        public Llm {
            if (primaryModel == null || primaryModel.isBlank()) primaryModel = "claude-opus-5";
            if (fallbackModel == null || fallbackModel.isBlank()) fallbackModel = "claude-sonnet-5";
            if (effort == null || effort.isBlank()) effort = "high";
            if (maxTokens <= 0) maxTokens = 8000;
        }

        /** 未配置 Key 时整条链路降级为规则引擎，平台仍可完整演示。 */
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Runtime(int maxIterations, double routeConfidenceThreshold,
                          int contextWindowTokens, double compressTriggerRatio,
                          int loopRepeatThreshold) {
        public Runtime {
            if (maxIterations <= 0) maxIterations = 12;
            if (routeConfidenceThreshold <= 0) routeConfidenceThreshold = 0.7;
            if (contextWindowTokens <= 0) contextWindowTokens = 180_000;
            if (compressTriggerRatio <= 0) compressTriggerRatio = 0.7;
            if (loopRepeatThreshold <= 0) loopRepeatThreshold = 3;
        }
    }

    /**
     * 远程子 Agent（A2A）参数。
     *
     * <p>刻意与 {@link Resilience} 分开：工具调用是秒级、可重试的；
     * Agent 委派是分钟级、<b>不可重试</b>的（大概率有副作用），
     * 两者共用一套超时与重试参数必然有一边是错的。
     */
    public record A2a(int connectTimeoutSeconds, int readTimeoutSeconds, int taskTimeoutSeconds,
                      long pollIntervalMillis, int cardTimeoutSeconds,
                      Boolean requireDelegationApproval) {
        public A2a {
            if (connectTimeoutSeconds <= 0) connectTimeoutSeconds = 10;
            // 单帧读超时：流式场景下远端可能长时间静默但任务仍在推进
            if (readTimeoutSeconds <= 0) readTimeoutSeconds = 120;
            // 整个远程任务的墙钟上限
            if (taskTimeoutSeconds <= 0) taskTimeoutSeconds = 600;
            if (pollIntervalMillis <= 0) pollIntervalMillis = 2000;
            if (cardTimeoutSeconds <= 0) cardTimeoutSeconds = 10;
            // 默认开启委派前审批：把任务交给平台管不到的外部服务，是需要留痕的动作
            if (requireDelegationApproval == null) requireDelegationApproval = Boolean.TRUE;
        }
    }

    public record Resilience(int toolTimeoutSeconds, int toolMaxRetries,
                             double circuitFailureRate, int circuitWindowSeconds,
                             int circuitProbeSeconds) {
        public Resilience {
            if (toolTimeoutSeconds <= 0) toolTimeoutSeconds = 30;
            if (toolMaxRetries <= 0) toolMaxRetries = 3;
            if (circuitFailureRate <= 0) circuitFailureRate = 0.6;
            if (circuitWindowSeconds <= 0) circuitWindowSeconds = 300;
            if (circuitProbeSeconds <= 0) circuitProbeSeconds = 30;
        }
    }
}
