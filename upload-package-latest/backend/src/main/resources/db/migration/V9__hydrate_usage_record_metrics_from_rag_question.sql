UPDATE usage_record
SET
  model_name = COALESCE(
    model_name,
    (SELECT q.model_name FROM rag_question q WHERE q.id = usage_record.target_id)
  ),
  prompt_tokens = COALESCE(
    prompt_tokens,
    (SELECT q.prompt_tokens FROM rag_question q WHERE q.id = usage_record.target_id)
  ),
  completion_tokens = COALESCE(
    completion_tokens,
    (SELECT q.completion_tokens FROM rag_question q WHERE q.id = usage_record.target_id)
  ),
  total_tokens = COALESCE(
    total_tokens,
    (SELECT q.total_tokens FROM rag_question q WHERE q.id = usage_record.target_id)
  )
WHERE target_type = 'RAG_QUESTION'
  AND action IN ('RAG_CHAT', 'RAG_CHAT_STREAM')
  AND EXISTS (
    SELECT 1
    FROM rag_question q
    WHERE q.id = usage_record.target_id
  )
  AND (
    model_name IS NULL
    OR prompt_tokens IS NULL
    OR completion_tokens IS NULL
    OR total_tokens IS NULL
  );
