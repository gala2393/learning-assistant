ALTER TABLE user_llm_config
    ADD COLUMN display_name VARCHAR(128);

ALTER TABLE user_llm_config
    ADD COLUMN active BIT NOT NULL DEFAULT 0;

UPDATE user_llm_config
    SET display_name = COALESCE(NULLIF(model, ''), '自定义模型'),
        active = enabled;

ALTER TABLE user_llm_config
    DROP INDEX uk_user_llm_config_user;

CREATE INDEX idx_user_llm_config_user_active_updated
    ON user_llm_config (user_id, active, updated_at);
