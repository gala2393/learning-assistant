package com.mytext.learningassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DatabaseMigrationTest {

    @Test
    void baselineMigrationDefinesProductionRagSchema() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V1__baseline_schema.sql");
        String sql = Files.readString(migration);

        assertThat(sql).contains(
            "CREATE TABLE material_chunk",
            "CREATE TABLE rag_evaluation_suite",
            "CREATE TABLE rag_evaluation_suite_run",
            "CREATE TABLE rag_feedback",
            "CREATE TABLE rag_evaluation"
        );
        assertThat(sql).contains(
            "hierarchy_path VARCHAR(500)",
            "summary VARCHAR(500)",
            "keywords VARCHAR(500)",
            "idx_rag_eval_suite_scheduled_next",
            "idx_material_chunk_material_index"
        );
    }
}
