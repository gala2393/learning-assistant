package com.mytext.learningassistant.material;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 资料后台任务仓库。
 */
public interface MaterialProcessingJobRepository extends JpaRepository<MaterialProcessingJobEntity, Long> {

    List<MaterialProcessingJobEntity> findByMaterialIdOrderByCreatedAtDesc(Long materialId);

    boolean existsByMaterialIdAndJobTypeAndStatusIn(
        Long materialId,
        MaterialProcessingJobType jobType,
        Collection<MaterialProcessingJobStatus> statuses
    );

    @Query("""
        select job from MaterialProcessingJobEntity job
        where (job.status = :pendingStatus or (job.status = :retryWaitStatus and (job.runAfter is null or job.runAfter <= :now)))
        order by job.priority asc, job.createdAt asc
        """)
    List<MaterialProcessingJobEntity> findReadyJobs(
        @Param("pendingStatus") MaterialProcessingJobStatus pendingStatus,
        @Param("retryWaitStatus") MaterialProcessingJobStatus retryWaitStatus,
        @Param("now") LocalDateTime now,
        Pageable pageable
    );

    @Modifying
    @Query("""
        update MaterialProcessingJobEntity job
        set job.status = :runningStatus,
            job.lockedBy = :workerId,
            job.lockedAt = :now,
            job.startedAt = :now,
            job.attemptCount = job.attemptCount + 1,
            job.errorMessage = null
        where job.id = :jobId and
            (job.status = :pendingStatus or (job.status = :retryWaitStatus and (job.runAfter is null or job.runAfter <= :now)))
        """)
    int lockReadyJob(
        @Param("jobId") Long jobId,
        @Param("runningStatus") MaterialProcessingJobStatus runningStatus,
        @Param("pendingStatus") MaterialProcessingJobStatus pendingStatus,
        @Param("retryWaitStatus") MaterialProcessingJobStatus retryWaitStatus,
        @Param("workerId") String workerId,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
        update MaterialProcessingJobEntity job
        set job.status = :retryWaitStatus,
            job.errorMessage = :message,
            job.lockedBy = null,
            job.lockedAt = null,
            job.runAfter = :now
        where job.status = :runningStatus and job.lockedAt < :cutoff
        """)
    int releaseStaleRunningJobs(
        @Param("runningStatus") MaterialProcessingJobStatus runningStatus,
        @Param("retryWaitStatus") MaterialProcessingJobStatus retryWaitStatus,
        @Param("message") String message,
        @Param("cutoff") LocalDateTime cutoff,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
        update MaterialProcessingJobEntity job
        set job.status = :retryWaitStatus,
            job.errorMessage = :message,
            job.lockedBy = null,
            job.lockedAt = null,
            job.runAfter = :now
        where job.status = :runningStatus and job.jobType in :jobTypes and job.lockedAt < :cutoff
        """)
    int releaseStaleRunningJobsByTypes(
        @Param("runningStatus") MaterialProcessingJobStatus runningStatus,
        @Param("retryWaitStatus") MaterialProcessingJobStatus retryWaitStatus,
        @Param("jobTypes") Collection<MaterialProcessingJobType> jobTypes,
        @Param("message") String message,
        @Param("cutoff") LocalDateTime cutoff,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
        update MaterialProcessingJobEntity job
        set job.status = :cancelledStatus,
            job.errorMessage = :message,
            job.lockedBy = null,
            job.lockedAt = null,
            job.runAfter = null,
            job.finishedAt = :now
        where job.materialId = :materialId and job.status in :activeStatuses
        """)
    int cancelActiveJobsForMaterial(
        @Param("materialId") Long materialId,
        @Param("activeStatuses") Collection<MaterialProcessingJobStatus> activeStatuses,
        @Param("cancelledStatus") MaterialProcessingJobStatus cancelledStatus,
        @Param("message") String message,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
        update MaterialProcessingJobEntity job
        set job.status = :cancelledStatus,
            job.errorMessage = :message,
            job.lockedBy = null,
            job.lockedAt = null,
            job.runAfter = null,
            job.finishedAt = :now
        where job.id = :jobId
        """)
    int cancelJob(
        @Param("jobId") Long jobId,
        @Param("cancelledStatus") MaterialProcessingJobStatus cancelledStatus,
        @Param("message") String message,
        @Param("now") LocalDateTime now
    );
}
