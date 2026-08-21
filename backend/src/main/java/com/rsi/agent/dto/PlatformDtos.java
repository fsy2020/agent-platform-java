package com.rsi.agent.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.rsi.agent.domain.ApprovalEntity;
import com.rsi.agent.domain.ChatSessionEntity;
import com.rsi.agent.domain.EvalEntities.EvalCaseEntity;
import com.rsi.agent.domain.EvalEntities.EvalDatasetEntity;
import com.rsi.agent.domain.EvalEntities.EvalResultEntity;
import com.rsi.agent.domain.EvalEntities.EvalRunEntity;
import com.rsi.agent.domain.MessageEntity;
import com.rsi.agent.domain.TraceEntity;
import com.rsi.agent.domain.TraceSpanEntity;
import com.rsi.agent.domain.UserEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 认证、会话、审批、观测、评测相关 DTO。 */
public final class PlatformDtos {

    private PlatformDtos() {}

    // ---------------------------------------------------------------- 认证

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record UserDto(String id, String username, String displayName, String role, String tenantId) {
        public static UserDto from(UserEntity user) {
            return new UserDto(user.getId(), user.getUsername(), user.getDisplayName(),
                    user.getRole(), user.getTenantId());
        }
    }

    public record TokenDto(String accessToken, String tokenType, UserDto user) {
        public static TokenDto of(String token, UserEntity user) {
            return new TokenDto(token, "bearer", UserDto.from(user));
        }
    }

    // ---------------------------------------------------------------- 会话

    public record SessionCreateRequest(String title) {}

    public record ChatRequest(String sessionId, String message) {}

    public record SessionDto(String id, String title, String status, int turnCount, long totalTokens,
                             String summary, Instant createdAt, Instant updatedAt) {
        public static SessionDto from(ChatSessionEntity session) {
            return new SessionDto(session.getId(), session.getTitle(), session.getStatus(),
                    session.getTurnCount(), session.getTotalTokens(), session.getSummary(),
                    session.getCreatedAt(), session.getUpdatedAt());
        }
    }

    public record MessageDto(String id, String sessionId, String role, String content,
                             Map<String, Object> meta, Instant createdAt) {
        public static MessageDto from(MessageEntity message) {
            return new MessageDto(message.getId(), message.getSessionId(), message.getRole(),
                    message.getContent(), message.getMeta(), message.getCreatedAt());
        }
    }

    // ---------------------------------------------------------------- 审批

    public record ApprovalDto(String id, String sessionId, String traceId, String executorType,
                              String executorName, String toolName, Map<String, Object> toolArgs,
                              String riskLevel, String riskReason, String impactScope, String status,
                              String decidedBy, String decisionComment, Instant decidedAt,
                              Instant createdAt) {
        public static ApprovalDto from(ApprovalEntity approval) {
            return new ApprovalDto(approval.getId(), approval.getSessionId(), approval.getTraceId(),
                    approval.getExecutorType(), approval.getExecutorName(), approval.getToolName(),
                    approval.getToolArgs(), approval.getRiskLevel(), approval.getRiskReason(),
                    approval.getImpactScope(), approval.getStatus(), approval.getDecidedBy(),
                    approval.getDecisionComment(), approval.getDecidedAt(), approval.getCreatedAt());
        }
    }

    public record ApprovalDecisionRequest(
            @Pattern(regexp = "approved|rejected", message = "decision 必须是 approved 或 rejected")
            String decision,
            String comment) {}

    // ---------------------------------------------------------------- 观测

    public record TraceSpanDto(String id, int sequence, String name, String spanType,
                               Map<String, Object> input, Map<String, Object> output, String status,
                               String error, long latencyMs, Instant createdAt) {
        public static TraceSpanDto from(TraceSpanEntity span) {
            return new TraceSpanDto(span.getId(), span.getSequence(), span.getName(),
                    span.getSpanType(), span.getInput(), span.getOutput(), span.getStatus(),
                    span.getError(), span.getLatencyMs(), span.getCreatedAt());
        }
    }

    public record TraceDto(String id, String sessionId, int turnIndex, String userId, String tenantId,
                           String question, String answer, String routeType, String routeTarget,
                           double routeConfidence, String routeReason, int visibleToolCount,
                           int totalToolCount, int promptChars, int promptTokens, int completionTokens,
                           int totalTokens, int cacheHitTokens, long latencyMs, int iterations,
                           int toolCalls, int toolFailures, boolean contextCompressed, String modelUsed,
                           boolean modelFallback, boolean degradedToRules, String status,
                           String errorType, String errorMessage, String recoveryAction,
                           Instant createdAt, List<TraceSpanDto> spans) {
        public static TraceDto from(TraceEntity trace, List<TraceSpanEntity> spans) {
            return new TraceDto(trace.getId(), trace.getSessionId(), trace.getTurnIndex(),
                    trace.getUserId(), trace.getTenantId(), trace.getQuestion(), trace.getAnswer(),
                    trace.getRouteType(), trace.getRouteTarget(), trace.getRouteConfidence(),
                    trace.getRouteReason(), trace.getVisibleToolCount(), trace.getTotalToolCount(),
                    trace.getPromptChars(), trace.getPromptTokens(), trace.getCompletionTokens(),
                    trace.getTotalTokens(), trace.getCacheHitTokens(), trace.getLatencyMs(),
                    trace.getIterations(), trace.getToolCalls(), trace.getToolFailures(),
                    trace.isContextCompressed(), trace.getModelUsed(), trace.isModelFallback(),
                    trace.isDegradedToRules(), trace.getStatus(), trace.getErrorType(),
                    trace.getErrorMessage(), trace.getRecoveryAction(), trace.getCreatedAt(),
                    spans == null ? List.of() : spans.stream().map(TraceSpanDto::from).toList());
        }
    }

    // ---------------------------------------------------------------- 评测

    public record EvalCaseDto(String id, String datasetId, String question, String expectedRouteType,
                              String expectedTarget, String expectedAnswer, List<String> tags,
                              Instant createdAt) {
        public static EvalCaseDto from(EvalCaseEntity item) {
            return new EvalCaseDto(item.getId(), item.getDatasetId(), item.getQuestion(),
                    item.getExpectedRouteType(), item.getExpectedTarget(), item.getExpectedAnswer(),
                    item.getTags(), item.getCreatedAt());
        }
    }

    public record EvalCaseCreateRequest(String question, String expectedRouteType,
                                        String expectedTarget, String expectedAnswer,
                                        List<String> tags) {}

    public record EvalDatasetDto(String id, String name, String description, String owner,
                                 Instant createdAt, List<EvalCaseDto> cases) {
        public static EvalDatasetDto from(EvalDatasetEntity dataset, List<EvalCaseEntity> cases) {
            return new EvalDatasetDto(dataset.getId(), dataset.getName(), dataset.getDescription(),
                    dataset.getOwner(), dataset.getCreatedAt(),
                    cases.stream().map(EvalCaseDto::from).toList());
        }
    }

    public record EvalDatasetCreateRequest(String name, String description,
                                           List<EvalCaseCreateRequest> cases) {}

    public record EvalRunDto(String id, String datasetId, String name, String status, int total,
                             int finished, Map<String, Object> metrics, String triggeredBy,
                             Instant createdAt, Instant finishedAt) {
        public static EvalRunDto from(EvalRunEntity run) {
            return new EvalRunDto(run.getId(), run.getDatasetId(), run.getName(), run.getStatus(),
                    run.getTotal(), run.getFinished(), run.getMetrics(), run.getTriggeredBy(),
                    run.getCreatedAt(), run.getFinishedAt());
        }
    }

    public record EvalResultDto(String id, String runId, String caseId, String question, String answer,
                                String expectedRouteType, String expectedTarget,
                                String actualRouteType, String actualTarget, boolean routeCorrect,
                                boolean toolSuccess, long latencyMs, int totalTokens, Double llmScore,
                                String llmComment, String humanLabel, String humanComment,
                                String traceId, String error) {
        public static EvalResultDto from(EvalResultEntity result) {
            return new EvalResultDto(result.getId(), result.getRunId(), result.getCaseId(),
                    result.getQuestion(), result.getAnswer(), result.getExpectedRouteType(),
                    result.getExpectedTarget(), result.getActualRouteType(), result.getActualTarget(),
                    result.isRouteCorrect(), result.isToolSuccess(), result.getLatencyMs(),
                    result.getTotalTokens(), result.getLlmScore(), result.getLlmComment(),
                    result.getHumanLabel(), result.getHumanComment(), result.getTraceId(),
                    result.getError());
        }
    }

    public record HumanLabelRequest(
            @Pattern(regexp = "pass|fail", message = "human_label 必须是 pass 或 fail")
            String humanLabel,
            String humanComment) {}
}
