package com.mytext.learningassistant.material;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 智能问答临时资料全文清理服务。
 *
 * <p>临时资料用于跨轮问答恢复上下文，但不应像资料管理中的正式资料一样长期保存。
 * 默认保留 48 小时，既覆盖用户短期追问，又避免大附件全文长期堆积在数据库。</p>
 */
@Service
public class TemporaryMaterialContextCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TemporaryMaterialContextCleanupService.class);

    private final TemporaryMaterialContextRepository repository;
    private final Duration retention;

    public TemporaryMaterialContextCleanupService(
        TemporaryMaterialContextRepository repository,
        @Value("${app.material.temporary-context-retention:48h}") Duration retention
    ) {
        this.repository = repository;
        this.retention = retention == null || retention.isNegative() || retention.isZero()
            ? Duration.ofHours(48)
            : retention;
    }

    /**
     * 定时删除过期临时资料全文。
     *
     * <p>只删除临时资料正文表，不影响已保存的问答历史；历史里仍保留文件名和摘要用于回显。</p>
     */
    @Scheduled(fixedDelayString = "${app.material.temporary-context-cleanup-delay:1h}")
    public void cleanupExpiredContexts() {
        LocalDateTime cutoff = LocalDateTime.now().minus(retention);
        int deleted = repository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("Cleaned expired temporary material contexts: count={}, retention={}", deleted, retention);
        }
    }
}
