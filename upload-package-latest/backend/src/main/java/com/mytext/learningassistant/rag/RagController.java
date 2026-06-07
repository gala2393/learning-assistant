package com.mytext.learningassistant.rag;

import java.util.List;

import com.mytext.learningassistant.common.ApiResponse;
import com.mytext.learningassistant.security.RateLimitService;

import jakarta.servlet.http.HttpServletRequest;
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

/**
 * RAG（检索增强生成）模块的 REST 控制器。
 * <p>
 * 提供以下核心功能的 HTTP 接口：
 * <ul>
 *   <li>智能问答（普通对话 / 资料问答）</li>
 *   <li>对话历史的增删改查、重命名、置顶</li>
 *   <li>用户反馈提交</li>
 *   <li>单条问答评估与评估套件管理（创建、运行、定时调度）</li>
 *   <li>学习资料摘要的生成与查询</li>
 *   <li>用户每日使用额度查询</li>
 * </ul>
 * <p>
 * 所有接口均需要通过拦截器注入 {@code currentUserId}，代表当前登录用户。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    /** RAG 业务逻辑服务，负责检索、对话、评估等核心功能 */
    private final RagService ragService;
    private final RateLimitService rateLimitService;

    /**
     * 构造方法，由 Spring 自动注入 RagService。
     *
     * @param ragService RAG 业务服务实例
     */
    public RagController(RagService ragService, RateLimitService rateLimitService) {
        this.ragService = ragService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * 查询当前用户的每日使用额度信息。
     * <p>
     * 返回每日总限额、已使用次数、剩余次数，管理员或自定义模型用户为无限制。
     *
     * @param currentUserId 当前登录用户 ID（由拦截器注入）
     * @return 使用额度信息，包含 dailyLimit、usedToday、remainingToday、unlimited
     */
    @GetMapping("/usage")
    public ApiResponse<RagUsageResponse> usage(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(ragService.usage(currentUserId));
    }

    /**
     * 发送问答请求（非流式）。
     * <p>
     * 根据请求中的 mode 字段决定是"通用对话"还是"资料问答"，
     * 系统会自动检索相关资料片段作为上下文，交给大语言模型生成回答。
     *
     * @param currentUserId 当前登录用户 ID
     * @param request       问答请求体，包含问题文本、资料 ID、对话模式等
     * @return 问答响应，包含回答文本、引用来源、对话 ID 等
     */
    @PostMapping("/chat")
    public ApiResponse<RagChatResponse> chat(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody ChatRequest request,
        HttpServletRequest httpRequest
    ) {
        rateLimitService.checkRagChat(rateIdentity(currentUserId, httpRequest));
        return ApiResponse.ok(ragService.chat(currentUserId, request));
    }

    /**
     * 获取当前用户的对话历史列表。
     * <p>
     * 返回的是按会话（conversationId）分组后的最新一条记录，
     * 置顶的对话排在前面。
     *
     * @param currentUserId 当前登录用户 ID
     * @return 对话历史列表，每项包含对话 ID、标题、最新问答等
     */
    @GetMapping("/history")
    public ApiResponse<List<RagHistoryItemResponse>> history(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(ragService.history(currentUserId));
    }

    /**
     * 获取某条对话历史的详细信息。
     * <p>
     * 返回该会话内的完整多轮对话消息列表及最新回答的引用来源。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            对话中任意一条问答记录的 ID
     * @return 对话详情，包含多轮消息、引用来源、收藏状态等
     */
    @GetMapping("/history/{id}")
    public ApiResponse<RagHistoryDetailResponse> historyDetail(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.historyDetail(currentUserId, id));
    }

    /**
     * 删除指定对话历史及其整个会话。
     * <p>
     * 会同时删除该会话下所有问答记录的收藏、来源、反馈和评估数据。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            对话中任意一条问答记录的 ID
     * @return 空响应体
     */
    @DeleteMapping("/history/{id}")
    public ApiResponse<Void> deleteHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        ragService.deleteHistory(currentUserId, id);
        return ApiResponse.ok(null);
    }

    /**
     * 重命名指定对话的标题。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            对话中任意一条问答记录的 ID
     * @param request       包含新标题的请求体
     * @return 更新后的对话历史项
     */
    @PatchMapping("/history/{id}/title")
    public ApiResponse<RagHistoryItemResponse> renameHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @RequestBody RenameHistoryRequest request
    ) {
        return ApiResponse.ok(ragService.renameHistory(currentUserId, id, request.title()));
    }

    /**
     * 切换指定对话的置顶状态。
     * <p>
     * 如果当前已置顶则取消置顶，否则置顶。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            对话中任意一条问答记录的 ID
     * @return 切换置顶状态后的对话历史项
     */
    @PatchMapping("/history/{id}/pin")
    public ApiResponse<RagHistoryItemResponse> togglePinHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.togglePinHistory(currentUserId, id));
    }

    /**
     * 提交或更新对某条问答的用户反馈。
     * <p>
     * 反馈内容包括评分（1 或 -1）和可选的文字评论。同一用户对同一问答仅保留一条反馈记录。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            问答记录 ID
     * @param request       反馈请求体，包含 rating 和 comment
     * @return 保存后的反馈信息
     */
    @PatchMapping("/history/{id}/feedback")
    public ApiResponse<RagFeedbackResponse> submitFeedback(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @RequestBody RagFeedbackRequest request
    ) {
        return ApiResponse.ok(ragService.submitFeedback(currentUserId, id, request));
    }

    /**
     * 对指定问答执行评估，计算忠实度和上下文相关性等指标。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            问答记录 ID
     * @return 评估结果，包含各维度评分、判定结论和证据
     */
    @PostMapping("/history/{id}/evaluation")
    public ApiResponse<RagEvaluationResponse> evaluateHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.evaluateHistory(currentUserId, id));
    }

    /**
     * 获取指定问答的最新评估结果。
     * <p>
     * 如果尚未评估过，则自动触发一次评估。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            问答记录 ID
     * @return 评估结果
     */
    @GetMapping("/history/{id}/evaluation")
    public ApiResponse<RagEvaluationResponse> latestEvaluation(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.latestEvaluation(currentUserId, id));
    }

    /**
     * 运行一次性评估套件（不保存到数据库）。
     * <p>
     * 请求体包含多条测试用例，系统会逐条执行问答并评估，返回汇总结果。
     *
     * @param currentUserId 当前登录用户 ID
     * @param request       评估套件请求，包含多条测试用例
     * @return 评估套件汇总结果
     */
    @PostMapping("/evaluation-suite")
    public ApiResponse<RagEvaluationSuiteResponse> runEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody RagEvaluationSuiteRequest request
    ) {
        return ApiResponse.ok(ragService.runEvaluationSuite(currentUserId, request));
    }

    /**
     * 获取当前用户保存的所有评估套件摘要列表。
     *
     * @param currentUserId 当前登录用户 ID
     * @return 评估套件摘要列表
     */
    @GetMapping("/evaluation-suites")
    public ApiResponse<List<RagEvaluationSuiteSummaryResponse>> evaluationSuites(
        @RequestAttribute("currentUserId") long currentUserId
    ) {
        return ApiResponse.ok(ragService.evaluationSuites(currentUserId));
    }

    /**
     * 保存一个新的评估套件。
     *
     * @param currentUserId 当前登录用户 ID
     * @param request       评估套件保存请求，包含名称、描述和测试用例列表
     * @return 保存后的评估套件详情
     */
    @PostMapping("/evaluation-suites")
    public ApiResponse<RagEvaluationSuiteDetailResponse> saveEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody RagEvaluationSuiteSaveRequest request
    ) {
        return ApiResponse.ok(ragService.saveEvaluationSuite(currentUserId, request));
    }

    /**
     * 获取指定评估套件的详细信息。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            评估套件 ID
     * @return 评估套件详情
     */
    @GetMapping("/evaluation-suites/{id}")
    public ApiResponse<RagEvaluationSuiteDetailResponse> evaluationSuiteDetail(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.evaluationSuiteDetail(currentUserId, id));
    }

    /**
     * 更新已有的评估套件（名称、描述和测试用例）。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            评估套件 ID
     * @param request       更新请求体
     * @return 更新后的评估套件详情
     */
    @PutMapping("/evaluation-suites/{id}")
    public ApiResponse<RagEvaluationSuiteDetailResponse> updateEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @Valid @RequestBody RagEvaluationSuiteSaveRequest request
    ) {
        return ApiResponse.ok(ragService.updateEvaluationSuite(currentUserId, id, request));
    }

    /**
     * 删除指定评估套件及其所有运行记录和测试用例。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            评估套件 ID
     * @return 空响应体
     */
    @DeleteMapping("/evaluation-suites/{id}")
    public ApiResponse<Void> deleteEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        ragService.deleteEvaluationSuite(currentUserId, id);
        return ApiResponse.ok(null);
    }

    /**
     * 运行已保存的评估套件，并将运行结果持久化到数据库。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            评估套件 ID
     * @return 本次运行结果
     */
    @PostMapping("/evaluation-suites/{id}/runs")
    public ApiResponse<RagEvaluationSuiteRunResponse> runSavedEvaluationSuite(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.runSavedEvaluationSuite(currentUserId, id));
    }

    /**
     * 获取指定评估套件的历史运行记录。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            评估套件 ID
     * @return 运行记录列表，按创建时间倒序排列
     */
    @GetMapping("/evaluation-suites/{id}/runs")
    public ApiResponse<List<RagEvaluationSuiteRunResponse>> evaluationSuiteRuns(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(ragService.evaluationSuiteRuns(currentUserId, id));
    }

    /**
     * 更新评估套件的定时调度配置。
     * <p>
     * 可设置是否启用定时运行以及运行间隔（小时）。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            评估套件 ID
     * @param request       调度配置请求体
     * @return 更新后的评估套件详情
     */
    @PatchMapping("/evaluation-suites/{id}/schedule")
    public ApiResponse<RagEvaluationSuiteDetailResponse> updateEvaluationSuiteSchedule(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @Valid @RequestBody RagEvaluationSuiteScheduleRequest request
    ) {
        return ApiResponse.ok(ragService.updateEvaluationSuiteSchedule(currentUserId, id, request));
    }

    /**
     * 清空当前用户的全部对话历史。
     * <p>
     * 同时删除所有关联的收藏、来源、反馈和评估数据。
     *
     * @param currentUserId 当前登录用户 ID
     * @return 空响应体
     */
    @DeleteMapping("/history")
    public ApiResponse<Void> clearHistory(@RequestAttribute("currentUserId") long currentUserId) {
        ragService.clearHistory(currentUserId);
        return ApiResponse.ok(null);
    }

    /**
     * 对指定学习资料生成智能摘要。
     * <p>
     * 使用大语言模型对资料的前几个片段进行总结，生成摘要文本并保存。
     *
     * @param currentUserId 当前登录用户 ID
     * @param request       摘要请求体，包含资料 ID
     * @return 生成的摘要信息
     */
    @PostMapping("/summarize")
    public ApiResponse<RagSummaryResponse> summarize(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody SummarizeRequest request
    ) {
        return ApiResponse.ok(ragService.summarize(currentUserId, request));
    }

    /**
     * 获取指定资料的最新摘要。
     *
     * @param currentUserId 当前登录用户 ID
     * @param materialId    资料 ID
     * @return 最新的摘要信息
     */
    @GetMapping("/summaries/{materialId}")
    public ApiResponse<RagSummaryResponse> latestSummary(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("materialId") long materialId
    ) {
        return ApiResponse.ok(ragService.latestSummary(currentUserId, materialId));
    }

    /**
     * 获取指定资料的历史摘要列表。
     *
     * @param currentUserId 当前登录用户 ID
     * @param materialId    资料 ID
     * @return 摘要历史列表，按创建时间倒序排列
     */
    @GetMapping("/summaries/{materialId}/history")
    public ApiResponse<List<RagSummaryResponse>> summaryHistory(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("materialId") long materialId
    ) {
        return ApiResponse.ok(ragService.summaryHistory(currentUserId, materialId));
    }

    private String rateIdentity(long currentUserId, HttpServletRequest request) {
        return currentUserId + ":" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
