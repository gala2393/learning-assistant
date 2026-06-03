CREATE TABLE sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(128),
    password_hash VARCHAR(128) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    avatar TEXT,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    CONSTRAINT uk_sys_user_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE learning_material (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    source_url VARCHAR(512),
    file_size BIGINT NOT NULL,
    parse_status VARCHAR(20) NOT NULL,
    summary_status VARCHAR(20) NOT NULL,
    preview_status VARCHAR(20) NOT NULL,
    preview_error TEXT,
    page_count INT,
    chunk_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_learning_material_owner_created (owner_id, created_at),
    INDEX idx_learning_material_owner_updated (owner_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE material_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    chunk_text TEXT NOT NULL,
    page_no INT,
    section_title VARCHAR(255),
    hierarchy_path VARCHAR(500),
    summary VARCHAR(500),
    keywords VARCHAR(500),
    embedding_json TEXT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_material_chunk_index UNIQUE (material_id, chunk_index),
    INDEX idx_material_chunk_material_index (material_id, chunk_index),
    INDEX idx_material_chunk_material_page (material_id, page_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE material_upload_session (
    session_id VARCHAR(64) NOT NULL,
    client_upload_id VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    source_url VARCHAR(512),
    file_size BIGINT NOT NULL,
    chunk_size INT NOT NULL,
    total_chunks INT NOT NULL,
    uploaded_chunks INT NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    checksum_sha256 VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    material_id BIGINT,
    error_message TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT uk_material_upload_owner_client UNIQUE (owner_id, client_upload_id),
    INDEX idx_material_upload_owner_created (owner_id, created_at),
    INDEX idx_material_upload_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE material_summary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    summary_text TEXT NOT NULL,
    summary_type VARCHAR(40) NOT NULL,
    model_name VARCHAR(80) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_material_summary_material_user_created (material_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rag_question (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT,
    question_text TEXT NOT NULL,
    title TEXT,
    pinned BIT NOT NULL,
    answer_text TEXT NOT NULL,
    model_name VARCHAR(64) NOT NULL,
    question_status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_rag_question_user_created (user_id, created_at),
    INDEX idx_rag_question_user_pinned_created (user_id, pinned, created_at),
    INDEX idx_rag_question_user_conversation_created (user_id, conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rag_question_source (
    id BIGINT NOT NULL AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    source_title VARCHAR(255) NOT NULL,
    page_no INT,
    excerpt TEXT NOT NULL,
    rank_score DOUBLE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_rag_question_source_question_score (question_id, rank_score),
    INDEX idx_rag_question_source_material (material_id),
    INDEX idx_rag_question_source_chunk (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_favorite (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_favorite_user_question UNIQUE (user_id, question_id),
    INDEX idx_user_favorite_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rag_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    rating INT NOT NULL,
    comment TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rag_feedback_user_question UNIQUE (user_id, question_id),
    INDEX idx_rag_feedback_question_user (question_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rag_evaluation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    faithfulness_score DOUBLE NOT NULL,
    context_relevance_score DOUBLE NOT NULL,
    overall_score DOUBLE NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    evidence TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rag_evaluation_question UNIQUE (question_id),
    INDEX idx_rag_evaluation_user_question (user_id, question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rag_evaluation_suite (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    last_total_cases INT,
    last_passed_cases INT,
    last_pass_rate DOUBLE,
    last_average_overall_score DOUBLE,
    last_run_at DATETIME(6),
    scheduled BIT NOT NULL DEFAULT 0,
    schedule_interval_hours INT NOT NULL DEFAULT 24,
    next_run_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_rag_eval_suite_user_updated (user_id, updated_at),
    INDEX idx_rag_eval_suite_scheduled_next (scheduled, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rag_evaluation_suite_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    suite_id BIGINT NOT NULL,
    case_index INT NOT NULL,
    question VARCHAR(2000) NOT NULL,
    material_id BIGINT,
    expected_answer_terms TEXT,
    expected_source_terms TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rag_eval_suite_case_index UNIQUE (suite_id, case_index),
    INDEX idx_rag_eval_suite_case_suite (suite_id),
    INDEX idx_rag_eval_suite_case_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rag_evaluation_suite_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    suite_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    total_cases INT NOT NULL,
    passed_cases INT NOT NULL,
    pass_rate DOUBLE NOT NULL,
    average_faithfulness_score DOUBLE NOT NULL,
    average_context_relevance_score DOUBLE NOT NULL,
    average_overall_score DOUBLE NOT NULL,
    result_json TEXT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_rag_eval_suite_run_suite_user_created (suite_id, user_id, created_at),
    INDEX idx_rag_eval_suite_run_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT,
    detail TEXT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_system_log_created (created_at),
    INDEX idx_system_log_actor_created (actor_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
