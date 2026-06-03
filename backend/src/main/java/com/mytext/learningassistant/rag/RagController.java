package com.mytext.learningassistant.rag;

import java.util.List;

import com.mytext.learningassistant.common.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @GetMapping("/usage")
    public ApiResponse<RagUsageResponse> usage(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(ragService.usage(currentUserId));
    }

    @PostMapping("/chat")
    public ApiResponse<RagChatResponse> chat(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody ChatRequest request
    ) {
        return ApiResponse.ok(ragService.chat(currentUserId, request));
    }

    @GetMapping("/history")
    public ApiResponse<List<RagHistoryItemResponse>> history(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(ragService.history(currentUserId));
    }

    @GetMapping("/history/{id}")
    public ApiResponse<RagHistoryDetailResponse> historyDetail(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.historyDetail(currentUserId, id));
    }

    @DeleteMapping("/history/{id}")
    public ApiResponse<Void> deleteHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        ragService.deleteHistory(currentUserId, id);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/history/{id}/title")
    public ApiResponse<RagHistoryItemResponse> renameHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @RequestBody RenameHistoryRequest request
    ) {
        return ApiResponse.ok(ragService.renameHistory(currentUserId, id, request.title()));
    }

    @PatchMapping("/history/{id}/pin")
    public ApiResponse<RagHistoryItemResponse> togglePinHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.togglePinHistory(currentUserId, id));
    }

    @PatchMapping("/history/{id}/feedback")
    public ApiResponse<RagFeedbackResponse> submitFeedback(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @RequestBody RagFeedbackRequest request
    ) {
        return ApiResponse.ok(ragService.submitFeedback(currentUserId, id, request));
    }

    @PostMapping("/history/{id}/evaluation")
    public ApiResponse<RagEvaluationResponse> evaluateHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.evaluateHistory(currentUserId, id));
    }

    @GetMapping("/history/{id}/evaluation")
    public ApiResponse<RagEvaluationResponse> latestEvaluation(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.latestEvaluation(currentUserId, id));
    }

    @PostMapping("/evaluation-suite")
    public ApiResponse<RagEvaluationSuiteResponse> runEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody RagEvaluationSuiteRequest request
    ) {
        return ApiResponse.ok(ragService.runEvaluationSuite(currentUserId, request));
    }

    @GetMapping("/evaluation-suites")
    public ApiResponse<List<RagEvaluationSuiteSummaryResponse>> evaluationSuites(
        @RequestAttribute("currentUserId") long currentUserId
    ) {
        return ApiResponse.ok(ragService.evaluationSuites(currentUserId));
    }

    @PostMapping("/evaluation-suites")
    public ApiResponse<RagEvaluationSuiteDetailResponse> saveEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody RagEvaluationSuiteSaveRequest request
    ) {
        return ApiResponse.ok(ragService.saveEvaluationSuite(currentUserId, request));
    }

    @GetMapping("/evaluation-suites/{id}")
    public ApiResponse<RagEvaluationSuiteDetailResponse> evaluationSuiteDetail(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.evaluationSuiteDetail(currentUserId, id));
    }

    @PutMapping("/evaluation-suites/{id}")
    public ApiResponse<RagEvaluationSuiteDetailResponse> updateEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @Valid @RequestBody RagEvaluationSuiteSaveRequest request
    ) {
        return ApiResponse.ok(ragService.updateEvaluationSuite(currentUserId, id, request));
    }

    @DeleteMapping("/evaluation-suites/{id}")
    public ApiResponse<Void> deleteEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        ragService.deleteEvaluationSuite(currentUserId, id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/evaluation-suites/{id}/runs")
    public ApiResponse<RagEvaluationSuiteRunResponse> runSavedEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.runSavedEvaluationSuite(currentUserId, id));
    }

    @GetMapping("/evaluation-suites/{id}/runs")
    public ApiResponse<List<RagEvaluationSuiteRunResponse>> evaluationSuiteRuns(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.evaluationSuiteRuns(currentUserId, id));
    }

    @PatchMapping("/evaluation-suites/{id}/schedule")
    public ApiResponse<RagEvaluationSuiteDetailResponse> updateEvaluationSuiteSchedule(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @Valid @RequestBody RagEvaluationSuiteScheduleRequest request
    ) {
        return ApiResponse.ok(ragService.updateEvaluationSuiteSchedule(currentUserId, id, request));
    }

    @DeleteMapping("/history")
    public ApiResponse<Void> clearHistory(@RequestAttribute("currentUserId") long currentUserId) {
        ragService.clearHistory(currentUserId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/summarize")
    public ApiResponse<RagSummaryResponse> summarize(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody SummarizeRequest request
    ) {
        return ApiResponse.ok(ragService.summarize(currentUserId, request));
    }

    @GetMapping("/summaries/{materialId}")
    public ApiResponse<RagSummaryResponse> latestSummary(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("materialId") long materialId
    ) {
        return ApiResponse.ok(ragService.latestSummary(currentUserId, materialId));
    }

    @GetMapping("/summaries/{materialId}/history")
    public ApiResponse<List<RagSummaryResponse>> summaryHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("materialId") long materialId
    ) {
        return ApiResponse.ok(ragService.summaryHistory(currentUserId, materialId));
    }
}
