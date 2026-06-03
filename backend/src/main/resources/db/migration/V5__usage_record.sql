CREATE TABLE usage_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT,
    model_name VARCHAR(128),
    prompt_tokens INT,
    completion_tokens INT,
    total_tokens INT,
    detail TEXT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_usage_record_created (created_at),
    INDEX idx_usage_record_user_created (user_id, created_at),
    INDEX idx_usage_record_action_created (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
