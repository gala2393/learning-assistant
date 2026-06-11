CREATE TABLE temporary_material_context (
    id VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    title VARCHAR(128),
    original_name VARCHAR(255),
    source_type VARCHAR(40),
    text LONGTEXT NOT NULL,
    excerpt TEXT,
    file_size BIGINT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_temporary_material_context_owner_created (owner_id, created_at),
    INDEX idx_temporary_material_context_owner_id (owner_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
