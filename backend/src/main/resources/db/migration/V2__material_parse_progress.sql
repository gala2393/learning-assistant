ALTER TABLE learning_material
    ADD COLUMN parse_progress_percent INT NOT NULL DEFAULT 0 AFTER parse_status;

ALTER TABLE learning_material
    ADD COLUMN parse_stage VARCHAR(80) AFTER parse_progress_percent;

ALTER TABLE learning_material
    ADD COLUMN parse_message VARCHAR(255) AFTER parse_stage;
