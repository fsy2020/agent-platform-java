package com.rsi.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 评测相关实体集中在一个文件里 —— 它们字段少、生命周期一致，
 * 拆成四个文件反而增加导航成本。
 */
public final class EvalEntities {

    private EvalEntities() {}

    @Entity
    @Table(name = "eval_datasets")
    public static class EvalDatasetEntity extends BaseEntity {
        @Column(unique = true, nullable = false, length = 128)
        private String name;

        @Column(length = 2000)
        private String description = "";

        private String owner = "";

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }
        public String getOwner() { return owner; }
        public void setOwner(String v) { this.owner = v; }
    }

    @Entity
    @Table(name = "eval_cases")
    public static class EvalCaseEntity extends BaseEntity {
        @Column(nullable = false, length = 32)
        private String datasetId;

        @Column(length = 2000)
        private String question;

        private String expectedRouteType = "";
        private String expectedTarget = "";

        @Column(length = 2000)
        private String expectedAnswer = "";

        @Convert(converter = JsonAttributeConverter.StringListConverter.class)
        @Column(length = 1000)
        private List<String> tags = List.of();

        public String getDatasetId() { return datasetId; }
        public void setDatasetId(String v) { this.datasetId = v; }
        public String getQuestion() { return question; }
        public void setQuestion(String v) { this.question = v; }
        public String getExpectedRouteType() { return expectedRouteType; }
        public void setExpectedRouteType(String v) { this.expectedRouteType = v; }
        public String getExpectedTarget() { return expectedTarget; }
        public void setExpectedTarget(String v) { this.expectedTarget = v; }
        public String getExpectedAnswer() { return expectedAnswer; }
        public void setExpectedAnswer(String v) { this.expectedAnswer = v; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> v) { this.tags = v; }
    }

    @Entity
    @Table(name = "eval_runs")
    public static class EvalRunEntity extends BaseEntity {
        @Column(nullable = false, length = 32)
        private String datasetId;

        private String name = "";

        /** running | finished | failed */
        private String status = "running";

        private int total = 0;
        private int finished = 0;

        @Convert(converter = JsonAttributeConverter.MapConverter.class)
        @Column(length = 4000)
        private Map<String, Object> metrics = Map.of();

        private String triggeredBy = "";
        private Instant finishedAt;

        public String getDatasetId() { return datasetId; }
        public void setDatasetId(String v) { this.datasetId = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }
        public int getTotal() { return total; }
        public void setTotal(int v) { this.total = v; }
        public int getFinished() { return finished; }
        public void setFinished(int v) { this.finished = v; }
        public Map<String, Object> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Object> v) { this.metrics = v; }
        public String getTriggeredBy() { return triggeredBy; }
        public void setTriggeredBy(String v) { this.triggeredBy = v; }
        public Instant getFinishedAt() { return finishedAt; }
        public void setFinishedAt(Instant v) { this.finishedAt = v; }
    }

    @Entity
    @Table(name = "eval_results")
    public static class EvalResultEntity extends BaseEntity {
        @Column(nullable = false, length = 32)
        private String runId;

        private String caseId = "";

        @Column(length = 2000)
        private String question = "";

        @Column(length = 16000)
        private String answer = "";

        private String expectedRouteType = "";
        private String expectedTarget = "";
        private String actualRouteType = "";
        private String actualTarget = "";

        private boolean routeCorrect = false;
        private boolean toolSuccess = false;
        private long latencyMs = 0;
        private int totalTokens = 0;

        /** null 表示「待人工抽检」——审批中断与护栏阻断属于设计预期，不按 0 分处理。 */
        private Double llmScore;

        @Column(length = 2000)
        private String llmComment = "";

        private String humanLabel = "";

        @Column(length = 2000)
        private String humanComment = "";

        private String traceId = "";

        @Column(length = 2000)
        private String error = "";

        public String getRunId() { return runId; }
        public void setRunId(String v) { this.runId = v; }
        public String getCaseId() { return caseId; }
        public void setCaseId(String v) { this.caseId = v; }
        public String getQuestion() { return question; }
        public void setQuestion(String v) { this.question = v; }
        public String getAnswer() { return answer; }
        public void setAnswer(String v) { this.answer = v; }
        public String getExpectedRouteType() { return expectedRouteType; }
        public void setExpectedRouteType(String v) { this.expectedRouteType = v; }
        public String getExpectedTarget() { return expectedTarget; }
        public void setExpectedTarget(String v) { this.expectedTarget = v; }
        public String getActualRouteType() { return actualRouteType; }
        public void setActualRouteType(String v) { this.actualRouteType = v; }
        public String getActualTarget() { return actualTarget; }
        public void setActualTarget(String v) { this.actualTarget = v; }
        public boolean isRouteCorrect() { return routeCorrect; }
        public void setRouteCorrect(boolean v) { this.routeCorrect = v; }
        public boolean isToolSuccess() { return toolSuccess; }
        public void setToolSuccess(boolean v) { this.toolSuccess = v; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long v) { this.latencyMs = v; }
        public int getTotalTokens() { return totalTokens; }
        public void setTotalTokens(int v) { this.totalTokens = v; }
        public Double getLlmScore() { return llmScore; }
        public void setLlmScore(Double v) { this.llmScore = v; }
        public String getLlmComment() { return llmComment; }
        public void setLlmComment(String v) { this.llmComment = v; }
        public String getHumanLabel() { return humanLabel; }
        public void setHumanLabel(String v) { this.humanLabel = v; }
        public String getHumanComment() { return humanComment; }
        public void setHumanComment(String v) { this.humanComment = v; }
        public String getTraceId() { return traceId; }
        public void setTraceId(String v) { this.traceId = v; }
        public String getError() { return error; }
        public void setError(String v) { this.error = v; }
    }
}
