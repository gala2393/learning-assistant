package com.mytext.learningassistant.material;

/**
 * 资料后台流水线任务类型。
 */
public enum MaterialProcessingJobType {
    EXTRACT_TEXT_FAST,
    EXTRACT_TEXT_REMAINING,
    BUILD_PREVIEW,
    OCR_PAGE_BATCH,
    CHUNK_TEXT,
    BUILD_BM25,
    BUILD_EMBEDDING,
    SYNC_VECTOR_STORE
}
