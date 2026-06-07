CREATE TABLE user_llm_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    enabled BIT NOT NULL DEFAULT 0,
    base_url VARCHAR(512),
    api_key TEXT,
    model VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_llm_config_user UNIQUE (user_id),
    INDEX idx_user_llm_config_user_enabled (user_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE rag_question
    ADD COLUMN prompt_tokens INT AFTER model_name;

ALTER TABLE rag_question
    ADD COLUMN completion_tokens INT AFTER prompt_tokens;

ALTER TABLE rag_question
    ADD COLUMN total_tokens INT AFTER completion_tokens;

ALTER TABLE rag_question
    ADD COLUMN custom_model BIT NOT NULL DEFAULT 0 AFTER total_tokens;
