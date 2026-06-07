package com.mytext.learningassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VectorStoreDeploymentArtifactsTest {

    @Test
    void qdrantDeploymentArtifactsDocumentProductionVerification() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.qdrant.yml"));
        String verifier = Files.readString(Path.of("tools/verify-qdrant.ps1"));
        String doc = Files.readString(Path.of("docs/qdrant-production-check.md"));

        assertThat(compose).contains("qdrant/qdrant", "6333:6333", "qdrant_storage");
        assertThat(verifier).contains("VECTOR_STORE_BASE_URL", "VECTOR_STORE_COLLECTION", "/collections");
        assertThat(doc).contains("VECTOR_STORE_ENABLED=true", "verify-qdrant.ps1", "learning_assistant_chunks");
    }
}
