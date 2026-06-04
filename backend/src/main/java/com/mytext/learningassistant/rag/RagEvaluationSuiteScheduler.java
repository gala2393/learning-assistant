package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RAG 评估套件定时调度器 —— 定期扫描并执行到期的评估套件。
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>按照配置的间隔（默认 30 分钟）定期执行扫描</li>
 *   <li>查询所有已启用定时且下次执行时间已到期的评估套件</li>
 *   <li>逐个执行到期的套件，记录运行结果</li>
 *   <li>使用 {@link AtomicBoolean} 确保同一时刻不会重复执行（防止上一次扫描尚未完成时又触发新的扫描）</li>
 * </ol>
 */
@Component
public class RagEvaluationSuiteScheduler {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationSuiteScheduler.class);

    /** 调度器配置属性 */
    private final RagEvaluationSchedulerProperties properties;

    /** RAG 服务，用于执行实际的评估逻辑 */
    private final RagService ragService;

    /** 并发控制标志：确保同一时刻只有一个扫描任务在执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造函数。
     *
     * @param properties 调度器配置
     * @param ragService RAG 业务服务
     */
    public RagEvaluationSuiteScheduler(RagEvaluationSchedulerProperties properties, RagService ragService) {
        this.properties = properties;
        this.ragService = ragService;
    }

    /**
     * 定时任务入口 —— 按配置的间隔自动扫描并执行到期的评估套件。
     *
     * <p>通过 {@code @Scheduled(fixedDelayString)} 注解实现定时执行，
     * 使用 fixedDelay 确保上一次执行完成后才开始计时。</p>
     */
    @Scheduled(fixedDelayString = "${app.rag-evaluation-scheduler.scan-interval:30m}")
    public void runDueSuites() {
        // 如果调度器被禁用，直接返回
        if (!properties.isEnabled()) {
            return;
        }
        runDueSuitesOnce(LocalDateTime.now());
    }

    /**
     * 执行一次到期套件的扫描和运行。
     *
     * <p>使用 CAS（Compare-And-Swap）操作确保并发安全，
     * 避免多个线程同时执行评估任务。</p>
     *
     * @param now 当前时间，用于判断哪些套件已到期
     * @return 本次执行的套件数量
     */
    public int runDueSuitesOnce(LocalDateTime now) {
        // CAS 操作：如果当前没有任务在运行，则标记为正在运行
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            int runCount = 0;
            // 遍历所有到期的评估套件并逐个执行
            for (RagEvaluationSuiteEntity suite : ragService.dueScheduledEvaluationSuites(now)) {
                try {
                    ragService.runScheduledEvaluationSuite(suite.getId(), now);
                    runCount++;
                } catch (Exception exception) {
                    // 单个套件执行失败不影响其他套件的执行
                    log.warn("Scheduled RAG evaluation suite failed: suiteId={}", suite.getId(), exception);
                }
            }
            return runCount;
        } finally {
            // 无论成功还是异常，都要重置运行标志
            running.set(false);
        }
    }
}
