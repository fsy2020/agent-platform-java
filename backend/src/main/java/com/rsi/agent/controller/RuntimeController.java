package com.rsi.agent.controller;

import java.util.List;
import java.util.Map;

import com.rsi.agent.annotation.CurrentUser;
import com.rsi.agent.dto.PlatformDtos.*;
import com.rsi.agent.security.Identity;
import com.rsi.agent.service.ApprovalService;
import com.rsi.agent.service.ChatService;
import com.rsi.agent.service.EvaluationService;
import com.rsi.agent.service.TraceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/** 运行时 API：对话（SSE）、审批、观测、评测。 */
@RestController
@RequestMapping("/api")
public class RuntimeController {

    private final ChatService chatService;
    private final ApprovalService approvalService;
    private final TraceService traceService;
    private final EvaluationService evaluationService;

    public RuntimeController(ChatService chatService, ApprovalService approvalService,
                             TraceService traceService, EvaluationService evaluationService) {
        this.chatService = chatService;
        this.approvalService = approvalService;
        this.traceService = traceService;
        this.evaluationService = evaluationService;
    }

    // ---------------------------------------------------------------- 会话

    @GetMapping("/chat/sessions")
    public List<SessionDto> listSessions(@CurrentUser Identity identity) {
        return chatService.listSessions(identity);
    }

    @PostMapping("/chat/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDto createSession(@RequestBody SessionCreateRequest request,
                                    @CurrentUser Identity identity) {
        return chatService.createSession(request.title(), identity);
    }

    @DeleteMapping("/chat/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String sessionId, @CurrentUser Identity identity) {
        chatService.deleteSession(sessionId, identity);
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    public List<MessageDto> messages(@PathVariable String sessionId, @CurrentUser Identity identity) {
        return chatService.messages(sessionId, identity);
    }

    /**
     * 发送消息，SSE 流式返回编排全过程。
     *
     * <p>返回 {@link ResponseBodyEmitter}，Spring MVC 会挂起该请求直到 emitter 完成；
     * 真正的编排跑在虚拟线程上，不占用 Tomcat 工作线程。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter stream(@RequestBody ChatRequest request, @CurrentUser Identity identity) {
        return chatService.stream(request.sessionId(), request.message(), identity);
    }

    /** 审批放行后恢复被中断的编排。 */
    @PostMapping(value = "/chat/resume/{approvalId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter resume(@PathVariable String approvalId, @CurrentUser Identity identity) {
        return chatService.resume(approvalId, identity);
    }

    // ---------------------------------------------------------------- 审批

    @GetMapping("/approvals")
    public List<ApprovalDto> listApprovals(@RequestParam(required = false) String status,
                                           @RequestParam(name = "session_id", required = false)
                                           String sessionId) {
        return approvalService.list(status, sessionId);
    }

    @GetMapping("/approvals/{approvalId}")
    public ApprovalDto getApproval(@PathVariable String approvalId) {
        return approvalService.get(approvalId);
    }

    @PostMapping("/approvals/{approvalId}/decide")
    public ApprovalDto decide(@PathVariable String approvalId,
                              @Valid @RequestBody ApprovalDecisionRequest request,
                              @CurrentUser Identity identity) {
        return approvalService.decide(approvalId, request, identity);
    }

    // ---------------------------------------------------------------- 观测

    @GetMapping("/traces")
    public List<TraceDto> listTraces(@RequestParam(name = "session_id", required = false) String sessionId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(name = "route_type", required = false) String routeType,
                                     @RequestParam(defaultValue = "50") int limit) {
        return traceService.search(sessionId, status, routeType, limit);
    }

    @GetMapping("/traces/{traceId}")
    public TraceDto getTrace(@PathVariable String traceId) {
        return traceService.detail(traceId);
    }

    @GetMapping("/metrics/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "7") int days) {
        return traceService.overview(days);
    }

    @GetMapping("/metrics/tools")
    public List<Map<String, Object>> toolMetrics() {
        return traceService.toolMetrics();
    }

    @GetMapping("/metrics/capabilities")
    public Map<String, Object> capabilityMetrics() {
        return traceService.capabilityMetrics();
    }

    // ---------------------------------------------------------------- 评测

    @GetMapping("/evals/datasets")
    public List<EvalDatasetDto> listDatasets() {
        return evaluationService.listDatasets();
    }

    @PostMapping("/evals/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalDatasetDto createDataset(@RequestBody EvalDatasetCreateRequest request,
                                        @CurrentUser Identity identity) {
        return evaluationService.createDataset(request, identity);
    }

    @PostMapping("/evals/datasets/{datasetId}/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public EvalCaseDto addCase(@PathVariable String datasetId,
                               @RequestBody EvalCaseCreateRequest request) {
        return evaluationService.addCase(datasetId, request);
    }

    @DeleteMapping("/evals/cases/{caseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCase(@PathVariable String caseId) {
        evaluationService.deleteCase(caseId);
    }

    @PostMapping("/evals/datasets/{datasetId}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EvalRunDto startRun(@PathVariable String datasetId, @CurrentUser Identity identity) {
        return evaluationService.startRun(datasetId, identity);
    }

    @GetMapping("/evals/runs")
    public List<EvalRunDto> listRuns(@RequestParam(name = "dataset_id", required = false) String datasetId) {
        return evaluationService.listRuns(datasetId);
    }

    @GetMapping("/evals/runs/{runId}")
    public EvalRunDto getRun(@PathVariable String runId) {
        return evaluationService.getRun(runId);
    }

    @GetMapping("/evals/runs/{runId}/results")
    public List<EvalResultDto> listResults(@PathVariable String runId,
                                           @RequestParam(name = "only_failed", defaultValue = "false")
                                           boolean onlyFailed) {
        return evaluationService.listResults(runId, onlyFailed);
    }

    @PostMapping("/evals/results/{resultId}/label")
    public EvalResultDto label(@PathVariable String resultId,
                               @Valid @RequestBody HumanLabelRequest request) {
        return evaluationService.label(resultId, request);
    }
}
