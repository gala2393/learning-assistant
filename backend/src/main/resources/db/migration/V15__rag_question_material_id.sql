ALTER TABLE rag_question
  ADD COLUMN material_id BIGINT NULL AFTER conversation_id;

CREATE INDEX idx_rag_question_user_material_created
  ON rag_question (user_id, material_id, created_at);
