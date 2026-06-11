package com.mytext.learningassistant.material;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.mytext.learningassistant.common.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 数据库驱动的资料后台处理任务服务。
 *
 * <p>上传后的耗时步骤统一落到 material_processing_job 表中，worker 只在领取任务和写回结果时开启短事务，
 * 真正的文件解析、OCR、切片和索引构建在事务外执行，避免大文件处理长期占用数据库连接和锁。</p>
 */
@Service
public class MaterialProcessingJobService {

    private static final Logger log = LoggerFactory.getLogger(MaterialProcessingJobService.class);
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int BATCH_SIZE = 2;

    private final MaterialProcessingJobRepository jobRepository;
    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialService materialService;
    private final TransactionTemplate transactionTemplate;
    private final String workerId;
    private final boolean schedulerEnabled;
    private final Duration runningJobTimeout;
    private final Duration vectorRunningJobTimeout;

    public MaterialProcessingJobService(
        MaterialProcessingJobRepository jobRepository,
        LearningMaterialRepository learningMaterialRepository,
        @Lazy MaterialService materialService,
        PlatformTransactionManager transactionManager,
        @Value("${app.material-processing.scheduler-enabled:true}") boolean schedulerEnabled,
        @Value("${app.material-processing.running-timeout:10m}") Duration runningJobTimeout,
        @Value("${app.material-processing.vector-running-timeout:30s}") Duration vectorRunningJobTimeout
    ) {
        this.jobRepository = jobRepository;
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialService = materialService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // worker 的锁定、释放和写回必须始终使用独立短事务；在 afterCommit 回调里运行时不能复用已提交事务。
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.workerId = buildWorkerId();
        this.schedulerEnabled = schedulerEnabled;
        this.runningJobTimeout = runningJobTimeout == null || runningJobTimeout.isNegative() || runningJobTimeout.isZero()
            ? Duration.ofMinutes(10)
            : runningJobTimeout;
        this.vectorRunningJobTimeout = vectorRunningJobTimeout == null || vectorRunningJobTimeout.isNegative() || vectorRunningJobTimeout.isZero()
            ? Duration.ofSeconds(30)
            : vectorRunningJobTimeout;
    }

    /**
     * 为资料创建一个后台任务。
     */
    @Transactional
    public MaterialProcessingJobEntity enqueue(Long materialId, MaterialProcessingJobType jobType, int priority, String stage, String message) {
        MaterialProcessingJobEntity job = new MaterialProcessingJobEntity();
        job.setMaterialId(materialId);
        job.setJobType(jobType);
        job.setStatus(MaterialProcessingJobStatus.PENDING);
        job.setPriority(priority);
        job.setStage(stage);
        job.setMessage(message);
        job.setProgressPercent(0);
        return jobRepository.save(job);
    }

    /**
     * 为同一资料排入最多一个活跃的后台任务。
     * OCR 完成后只需要补一次全量向量索引；如果队列里已经有 PENDING/RUNNING/RETRY_WAIT 的同类任务，
     * 继续复用它即可，避免大 PDF 每个 OCR 批次都重复重建索引。
     */
    @Transactional
    public MaterialProcessingJobEntity enqueueIfNoActiveJob(
        Long materialId,
        MaterialProcessingJobType jobType,
        int priority,
        String stage,
        String message
    ) {
        if (jobRepository.existsByMaterialIdAndJobTypeAndStatusIn(materialId, jobType, activeStatuses())) {
            return null;
        }
        return enqueue(materialId, jobType, priority, stage, message);
    }

    /**
     * 查询指定资料的后台任务列表。
     */
    @Transactional(readOnly = true)
    public List<MaterialProcessingJobResponse> jobs(long ownerId, long materialId) {
        learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        return jobRepository.findByMaterialIdOrderByCreatedAtDesc(materialId)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    /**
     * 手动重试失败或等待重试的任务。
     */
    @Transactional
    public MaterialProcessingJobResponse retry(long ownerId, long materialId, long jobId) {
        learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        MaterialProcessingJobEntity job = jobRepository.findById(jobId)
            .filter(item -> materialId == item.getMaterialId())
            .orElseThrow(() -> new BusinessException(404, "Job not found"));
        job.setStatus(MaterialProcessingJobStatus.PENDING);
        job.setRunAfter(null);
        job.setLockedAt(null);
        job.setLockedBy(null);
        job.setFinishedAt(null);
        job.setProgressPercent(0);
        job.setErrorMessage(null);
        return toResponse(jobRepository.save(job));
    }

    /**
     * 取消某份资料仍未完成的后台任务。
     *
     * <p>删除资料时必须先取消队列任务，否则 worker 会继续领取旧任务并反复报
     * {@code Material not found}，用户看到的资料状态也会被后台重试噪声干扰。</p>
     */
    @Transactional
    public int cancelMaterialJobs(Long materialId, String message) {
        if (materialId == null) {
            return 0;
        }
        return jobRepository.cancelActiveJobsForMaterial(
            materialId,
            activeStatuses(),
            MaterialProcessingJobStatus.CANCELLED,
            message == null || message.isBlank() ? "资料已删除，后台任务已取消" : message,
            LocalDateTime.now()
        );
    }

    /**
     * 定时扫描数据库队列。测试或单机维护场景可通过 app.material-processing.scheduler-enabled=false 禁用。
     */
    /**
     * 判断长耗时任务是否已经被取消。
     * 删除资料或重新解析会把旧任务标记为 CANCELLED；解析/OCR 在写库前检查它，避免旧任务继续覆盖新状态。
     */
    @Transactional(readOnly = true)
    public boolean isJobCancelled(Long jobId) {
        if (jobId == null) {
            return false;
        }
        return jobRepository.findById(jobId)
            .map(job -> job.getStatus() == MaterialProcessingJobStatus.CANCELLED)
            .orElse(true);
    }

    @Scheduled(fixedDelayString = "${app.material-processing.scan-delay:2s}")
    public void scanAndRun() {
        if (!schedulerEnabled) {
            return;
        }
        runReadyJobs(BATCH_SIZE);
    }

    /**
     * 执行当前可运行任务，供定时器、测试和运维入口复用。
     */
    public int runReadyJobs(int limit) {
        releaseStaleRunningJobs();
        int safeLimit = Math.max(1, limit);
        List<Long> jobIds = transactionTemplate.execute(status -> jobRepository.findReadyJobs(
                MaterialProcessingJobStatus.PENDING,
                MaterialProcessingJobStatus.RETRY_WAIT,
                LocalDateTime.now(),
                PageRequest.of(0, safeLimit)
            )
            .stream()
            .map(MaterialProcessingJobEntity::getId)
            .toList());
        if (jobIds == null || jobIds.isEmpty()) {
            return 0;
        }
        int executed = 0;
        for (Long jobId : jobIds) {
            if (runIfLocked(jobId)) {
                executed += 1;
            }
        }
        return executed;
    }

    private boolean runIfLocked(Long jobId) {
        MaterialProcessingJobEntity lockedJob = lockJob(jobId);
        if (lockedJob == null) {
            return false;
        }
        if (lockedJob.getMaterialId() != null && !learningMaterialRepository.existsById(lockedJob.getMaterialId())) {
            markCancelled(lockedJob.getId(), "资料已不存在，后台任务已取消");
            return true;
        }
        try {
            materialService.executeProcessingJob(lockedJob);
            markSuccess(lockedJob.getId());
        } catch (Exception exception) {
            handleJobFailure(lockedJob.getId(), exception);
        }
        return true;
    }

    private int releaseStaleRunningJobs() {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minus(runningJobTimeout);
            int released = jobRepository.releaseStaleRunningJobs(
                MaterialProcessingJobStatus.RUNNING,
                MaterialProcessingJobStatus.RETRY_WAIT,
                "后台任务运行超时，已自动释放并等待重试",
                cutoff,
                now
            );
            if (released > 0) {
                // 只在真正释放任务时写日志，避免定时扫描每 2 秒刷屏。
                log.warn("Released stale material processing jobs: count={}, timeout={}", released, runningJobTimeout);
            }
            // 向量增强已经从主解析链路拆出，正常情况下只做轻量状态同步和异步投递；
            // 如果旧进程或页面切换导致这类任务锁住，应更快释放，避免资料卡片长时间停在“索引部分可用”。
            LocalDateTime vectorCutoff = now.minus(vectorRunningJobTimeout);
            int releasedVectorJobs = jobRepository.releaseStaleRunningJobsByTypes(
                MaterialProcessingJobStatus.RUNNING,
                MaterialProcessingJobStatus.RETRY_WAIT,
                List.of(MaterialProcessingJobType.BUILD_EMBEDDING, MaterialProcessingJobType.SYNC_VECTOR_STORE),
                "向量增强任务运行超时，已自动释放并等待重试",
                vectorCutoff,
                now
            );
            if (releasedVectorJobs > 0) {
                log.warn("Released stale vector material processing jobs: count={}, timeout={}", releasedVectorJobs, vectorRunningJobTimeout);
            }
            return released + releasedVectorJobs;
        });
    }

    private MaterialProcessingJobEntity lockJob(Long jobId) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int locked = jobRepository.lockReadyJob(
                jobId,
                MaterialProcessingJobStatus.RUNNING,
                MaterialProcessingJobStatus.PENDING,
                MaterialProcessingJobStatus.RETRY_WAIT,
                workerId,
                now
            );
            if (locked == 0) {
                return null;
            }
            return jobRepository.findById(jobId).orElse(null);
        });
    }

    private void markSuccess(Long jobId) {
        transactionTemplate.executeWithoutResult(status -> jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == MaterialProcessingJobStatus.CANCELLED) {
                return;
            }
            job.setStatus(MaterialProcessingJobStatus.SUCCESS);
            job.setProgressPercent(100);
            job.setFinishedAt(LocalDateTime.now());
            job.setLockedAt(null);
            job.setLockedBy(null);
            job.setRunAfter(null);
            jobRepository.save(job);
        }));
    }

    private void handleJobFailure(Long jobId, Exception exception) {
        transactionTemplate.executeWithoutResult(status -> jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == MaterialProcessingJobStatus.CANCELLED) {
                return;
            }
            if (job.getMaterialId() != null && !learningMaterialRepository.existsById(job.getMaterialId())) {
                jobRepository.cancelJob(job.getId(), MaterialProcessingJobStatus.CANCELLED, "资料已不存在，后台任务已取消", LocalDateTime.now());
                return;
            }
            log.warn("Material processing job failed: jobId={}, materialId={}, type={}",
                job.getId(), job.getMaterialId(), job.getJobType(), exception);
            int attemptCount = job.getAttemptCount() == null ? 1 : job.getAttemptCount();
            int maxAttempts = job.getMaxAttempts() == null ? 3 : job.getMaxAttempts();
            job.setErrorMessage(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            job.setLockedAt(null);
            job.setLockedBy(null);
            if (attemptCount < maxAttempts) {
                job.setStatus(MaterialProcessingJobStatus.RETRY_WAIT);
                job.setRunAfter(LocalDateTime.now().plus(backoff(attemptCount)));
            } else {
                job.setStatus(MaterialProcessingJobStatus.FAILED);
                job.setFinishedAt(LocalDateTime.now());
                materialService.markProcessingJobFailed(job);
            }
            jobRepository.save(job);
        }));
    }

    private Duration backoff(int attemptCount) {
        return Duration.ofSeconds(Math.min(120, Math.max(5, attemptCount * 10L)));
    }

    private void markCancelled(Long jobId, String message) {
        transactionTemplate.executeWithoutResult(status -> jobRepository.cancelJob(
            jobId,
            MaterialProcessingJobStatus.CANCELLED,
            message,
            LocalDateTime.now()
        ));
    }

    private List<MaterialProcessingJobStatus> activeStatuses() {
        return List.of(
            MaterialProcessingJobStatus.PENDING,
            MaterialProcessingJobStatus.RETRY_WAIT,
            MaterialProcessingJobStatus.RUNNING
        );
    }

    MaterialProcessingJobResponse toResponse(MaterialProcessingJobEntity job) {
        return new MaterialProcessingJobResponse(
            job.getId(),
            job.getMaterialId(),
            job.getJobType() == null ? null : job.getJobType().name(),
            job.getStatus() == null ? null : job.getStatus().name(),
            job.getPriority(),
            job.getAttemptCount(),
            job.getMaxAttempts(),
            job.getProgressPercent(),
            job.getStage(),
            job.getMessage(),
            job.getErrorMessage(),
            job.getLockedBy(),
            format(job.getLockedAt()),
            format(job.getStartedAt()),
            format(job.getFinishedAt()),
            format(job.getCreatedAt()),
            format(job.getUpdatedAt())
        );
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATETIME_FORMATTER);
    }

    private String buildWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (UnknownHostException exception) {
            return "material-worker-" + ProcessHandle.current().pid();
        }
    }
}
