-- structured_json：结构化摘要 JSON，保存分区块要点。
-- sources_json：摘要来源 JSON，保存可跳转的资料片段和页码。
-- user_note：用户在 AI 摘要基础上整理后的个人版本。
ALTER TABLE material_summary
    ADD COLUMN structured_json LONGTEXT;

ALTER TABLE material_summary
    ADD COLUMN sources_json LONGTEXT;

ALTER TABLE material_summary
    ADD COLUMN user_note TEXT;
