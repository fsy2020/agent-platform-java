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
        Resilience resilience
) {
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
