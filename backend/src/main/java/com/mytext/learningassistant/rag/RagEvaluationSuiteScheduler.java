package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RagEvaluationSuiteScheduler {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationSuiteScheduler.class);

    private final RagEvaluationSchedulerProperties properties;
    private final RagService ragService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RagEvaluationSuiteScheduler(RagEvaluationSchedulerProperties properties, RagService ragService) {
        this.properties = properties;
        this.ragService = ragService;
    }

    @Scheduled(fixedDelayString = "${app.rag-evaluation-scheduler.scan-interval:30m}")
    public void runDueSuites() {
        if (!properties.isEnabled()) {
            return;
        }
        runDueSuitesOnce(LocalDateTime.now());
    }

    public int runDueSuitesOnce(LocalDateTime now) {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            int runCount = 0;
            for (RagEvaluationSuiteEntity suite : ragService.dueScheduledEvaluationSuites(now)) {
                try {
                    ragService.runScheduledEvaluationSuite(suite.getId(), now);
                    runCount++;
                } catch (Exception exception) {
                    log.warn("Scheduled RAG evaluation suite failed: suiteId={}", suite.getId(), exception);
                }
            }
            return runCount;
        } finally {
            running.set(false);
        }
    }
}
