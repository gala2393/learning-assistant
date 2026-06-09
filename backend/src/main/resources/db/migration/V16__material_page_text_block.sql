CREATE TABLE material_page_text_block (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    page_no INT NOT NULL,
    block_index INT NOT NULL,
    text TEXT NOT NULL,
    block_type VARCHAR(40),
    source VARCHAR(40),
    chunk_id BIGINT,
    page_width DOUBLE,
    page_height DOUBLE,
    bbox_x DOUBLE,
    bbox_y DOUBLE,
    bbox_width DOUBLE,
    bbox_height DOUBLE,
    confidence DOUBLE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_material_page_text_material_page (material_id, page_no, block_index),
    INDEX idx_material_page_text_chunk (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE material_upload_session
    MODIFY COLUMN client_upload_id VARCHAR(180) NOT NULL;
