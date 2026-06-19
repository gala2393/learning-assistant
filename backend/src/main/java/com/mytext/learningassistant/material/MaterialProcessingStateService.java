package com.mytext.learningassistant.material;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资料处理状态推进服务。
 *
 * <p>资料上传、解析、OCR、索引会经过多个后台任务。这个服务集中负责把任务进度和任务结果同步到资料状态，
 * 避免状态字段散落在解析、预览、索引等流程里。</p>
 */
@Service
public class MaterialProcessingStateService {

    private final LearningMaterialRepository learningMaterialRepository;

    public MaterialProcessingStateService(LearningMaterialRepository learningMaterialRepository) {
        this.learningMaterialRepository = learningMaterialRepository;
    }

    /**
     * 更新资料处理进度。
     *
     * @param materialId 资料 ID
     * @param ownerId   资料所有者 ID
     * @param percent   进度百分比，会被限制在 0-100
     * @param stage     当前阶段文案
     * @param message   当前阶段说明
     */
    @Transactional
    public void updateProgress(Long materialId, Long ownerId, int percent, String stage, String message) {
        if (materialId == null || ownerId == null) {
            return;
        }
        learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .ifPresent(material -> {
                material.setProcessingProgressPercent(clampProgress(percent));
                material.setProcessingStage(stage);
                material.setProcessingMessage(message);
                learningMaterialRepository.save(material);
            });
    }

    /**
     * 标记资料的向量索引重建任务已经排队。
     *
     * <p>BM25 已可用时，资料仍可继续阅读和问答；这里仅把向量增强标成后台补齐状态。</p>
     */
    @Transactional
    public LearningMaterialEntity markIndexRebuildQueued(LearningMaterialEntity material) {
        if (material == null) {
            return null;
        }
        material.setIndexStatus(MaterialIndexStatus.PARTIAL);
        material.setProcessingProgressPercent(Math.max(80, nullToZero(material.getProcessingProgressPercent())));
        material.setProcessingStage("等待重建索引");
        material.setProcessingMessage("向量索引重建任务已排队，BM25 可继续使用");
        return learningMaterialRepository.save(material);
    }

    /**
     * 后台任务最终失败后，同步资料的可用状态。
     *
     * <p>向量增强失败不应影响阅读和基础问答；剩余页/OCR 失败时保留已有可用片段；
     * 只有基础文本抽取失败才把资料标成不可用。</p>
     */
    @Transactional
    public void markJobFailed(MaterialProcessingJobEntity job) {
        if (job == null || job.getMaterialId() == null) {
            return;
        }
        learningMaterialRepository.findById(job.getMaterialId()).ifPresent(material -> {
            if (job.getJobType() == MaterialProcessingJobType.BUILD_EMBEDDING
                || job.getJobType() == MaterialProcessingJobType.SYNC_VECTOR_STORE) {
                markVectorEnhancementFailed(material);
            } else if (job.getJobType() == MaterialProcessingJobType.EXTRACT_TEXT_REMAINING) {
                markRemainingTextFailed(material);
            } else if (job.getJobType() == MaterialProcessingJobType.OCR_PAGE_BATCH) {
                markOcrBatchFailed(material);
            } else {
                markTextFailed(material, job.getErrorMessage());
            }
            learningMaterialRepository.save(material);
        });
    }

    private void markVectorEnhancementFailed(LearningMaterialEntity material) {
        material.setIndexStatus(MaterialIndexStatus.READY);
        material.setProcessingProgressPercent(100);
        material.setProcessingStage("处理完成");
        material.setProcessingMessage("文本、预览和 BM25 已可用；向量增强失败，可在任务面板重试，不影响阅读和问答");
    }

    private void markRemainingTextFailed(LearningMaterialEntity material) {
        material.setTextStatus(MaterialTextStatus.PARTIAL);
        material.setIndexStatus(MaterialIndexStatus.PARTIAL);
        material.setProcessingProgressPercent(Math.max(85, nullToZero(material.getProcessingProgressPercent())));
        material.setProcessingStage("部分页面可用");
        material.setProcessingMessage(remainingTextFailureMessage(material));
    }

    private void markOcrBatchFailed(LearningMaterialEntity material) {
        material.setTextStatus(MaterialTextStatus.PARTIAL);
        material.setIndexStatus(MaterialIndexStatus.PARTIAL);
        material.setOcrStatus(MaterialOcrStatus.FAILED);
        material.setProcessingProgressPercent(100);
        material.setProcessingStage("图片页已入库");
        material.setProcessingMessage("PDF 页面已按页入库，但 OCR 后台识别失败；请检查 Tesseract/语言包配置后在任务面板重试");
    }

    private void markTextFailed(LearningMaterialEntity material, String errorMessage) {
        material.setTextStatus(MaterialTextStatus.FAILED);
        material.setIndexStatus(MaterialIndexStatus.FAILED);
        material.setProcessingStage("处理失败");
        material.setProcessingMessage(errorMessage);
    }

    private String remainingTextFailureMessage(LearningMaterialEntity material) {
        int textPages = material == null || material.getTextPageCount() == null ? 0 : material.getTextPageCount();
        int totalPages = material == null || material.getPageCount() == null ? 0 : material.getPageCount();
        if (totalPages > 0 && textPages > 0 && textPages < totalPages) {
            return "已保留前 " + textPages + "/" + totalPages + " 页可用片段，剩余页面补齐失败，可重新解析重试";
        }
        return "已保留当前可用片段，剩余页面补齐失败，可重新解析重试";
    }

    private int clampProgress(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
