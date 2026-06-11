ALTER TABLE learning_material ADD COLUMN upload_status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED';
ALTER TABLE learning_material ADD COLUMN text_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE learning_material ADD COLUMN index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE learning_material ADD COLUMN ocr_status VARCHAR(20) NOT NULL DEFAULT 'DISABLED';
ALTER TABLE learning_material ADD COLUMN processing_progress_percent INT NOT NULL DEFAULT 0;
ALTER TABLE learning_material ADD COLUMN processing_stage VARCHAR(80);
ALTER TABLE learning_material ADD COLUMN processing_message VARCHAR(255);
ALTER TABLE learning_material ADD COLUMN indexed_chunk_count INT NOT NULL DEFAULT 0;
ALTER TABLE learning_material ADD COLUMN text_page_count INT NOT NULL DEFAULT 0;

UPDATE learning_material
SET
    text_status = CASE
        WHEN parse_status = 'SUCCESS' THEN 'READY'
        WHEN parse_status = 'FAILED' THEN 'FAILED'
        WHEN parse_status = 'PARSING' THEN 'RUNNING'
        ELSE 'PENDING'
    END,
    index_status = CASE
        WHEN parse_status = 'SUCCESS' THEN 'READY'
        WHEN parse_status = 'FAILED' THEN 'FAILED'
        WHEN parse_status = 'PARSING' THEN 'RUNNING'
        ELSE 'PENDING'
    END,
    processing_progress_percent = parse_progress_percent,
    processing_stage = parse_stage,
    processing_message = parse_message,
    indexed_chunk_count = COALESCE(chunk_count, 0),
    text_page_count = COALESCE(page_count, 0);

CREATE TABLE material_processing_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    job_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    progress_percent INT NOT NULL DEFAULT 0,
    stage VARCHAR(80),
    message VARCHAR(255),
    error_message TEXT,
    locked_by VARCHAR(120),
    locked_at DATETIME(6),
    run_after DATETIME(6),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_material_processing_job_pick (status, run_after, priority, created_at),
    INDEX idx_material_processing_job_material (material_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE material_page (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    page_no INT NOT NULL,
    text MEDIUMTEXT,
    text_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ocr_status VARCHAR(20) NOT NULL DEFAULT 'DISABLED',
    preview_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    char_count INT NOT NULL DEFAULT 0,
    token_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_page_material_page (material_id, page_no),
    INDEX idx_material_page_material (material_id, page_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE material_chunk ADD COLUMN source_page_start INT;
ALTER TABLE material_chunk ADD COLUMN source_page_end INT;
ALTER TABLE material_chunk ADD COLUMN token_count INT NOT NULL DEFAULT 0;
ALTER TABLE material_chunk ADD COLUMN char_count INT NOT NULL DEFAULT 0;
ALTER TABLE material_chunk ADD COLUMN embedding_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE material_chunk ADD COLUMN index_status VARCHAR(20) NOT NULL DEFAULT 'READY';
