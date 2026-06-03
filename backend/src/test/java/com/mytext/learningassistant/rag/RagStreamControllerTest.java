package com.mytext.learningassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.llm.ThirdPartyLlmClient;
import com.mytext.learningassistant.material.LearningMaterialRepository;
import com.mytext.learningassistant.material.MaterialChunkRepository;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class RagStreamControllerTest {

    @Test
    void chatStreamWritesChunkEventBeforeServiceReturns() throws Exception {
        RagService ragService = mock(RagService.class);
        var output = new ChunkDetectingOutputStream();
        AtomicBoolean chunkVisibleBeforeServiceReturned = new AtomicBoolean(false);

        when(ragService.chatStream(eq(1L), any(ChatRequest.class), any()))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Consumer<String> onChunk = invocation.getArgument(2, Consumer.class);
                onChunk.accept("Hello ");
                chunkVisibleBeforeServiceReturned.set(output.awaitChunk());
                return new RagStreamResult(11L, 12L, "Hello world", List.of());
            });

        RagStreamController controller = new RagStreamController(
            ragService,
            mock(ThirdPartyLlmClient.class),
            mock(LearningMaterialRepository.class),
            mock(MaterialChunkRepository.class),
            new ObjectMapper()
        );

        ChatRequest request = new ChatRequest(
            "stream please",
            null,
            "GENERAL",
            null,
            null,
            null,
            null,
            null,
            "STUDY",
            List.of(),
            null
        );
        ResponseEntity<StreamingResponseBody> response = controller.chatStream(1L, request);

        response.getBody().writeTo(output);

        String body = output.asString();
        assertThat(chunkVisibleBeforeServiceReturned).isTrue();
        assertThat(body).contains("event: status");
        assertThat(body).contains("event: chunk");
        assertThat(body).contains("\"delta\":\"Hello \"");
        assertThat(body).contains("event: done");
        assertThat(body.indexOf("event: chunk")).isLessThan(body.indexOf("event: done"));
    }

    @Test
    void chatStreamSplitsLargeProviderDeltaIntoVisibleSseChunks() throws Exception {
        RagService ragService = mock(RagService.class);
        when(ragService.chatStream(eq(1L), any(ChatRequest.class), any()))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Consumer<String> onChunk = invocation.getArgument(2, Consumer.class);
                onChunk.accept("This is a long provider delta that should not be sent as one browser update.");
                return new RagStreamResult(11L, 12L, "done", List.of());
            });

        RagStreamController controller = new RagStreamController(
            ragService,
            mock(ThirdPartyLlmClient.class),
            mock(LearningMaterialRepository.class),
            mock(MaterialChunkRepository.class),
            new ObjectMapper()
        );

        ResponseEntity<StreamingResponseBody> response = controller.chatStream(1L, streamRequest());
        var output = new ChunkDetectingOutputStream();

        response.getBody().writeTo(output);

        String body = output.asString();
        assertThat(body.split("event: chunk", -1).length - 1).isGreaterThan(3);
        assertThat(body).contains("\"delta\":\"This is a \"");
        assertThat(body.indexOf("event: chunk")).isLessThan(body.indexOf("event: done"));
    }

    private ChatRequest streamRequest() {
        return new ChatRequest(
            "stream please",
            null,
            "GENERAL",
            null,
            null,
            null,
            null,
            null,
            "STUDY",
            List.of(),
            null
        );
    }

    private static class ChunkDetectingOutputStream extends ByteArrayOutputStream {
        private final CountDownLatch chunkLatch = new CountDownLatch(1);

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            super.write(bytes, offset, length);
            if (asString().contains("event: chunk")) {
                chunkLatch.countDown();
            }
        }

        private boolean awaitChunk() throws InterruptedException {
            return chunkLatch.await(1, TimeUnit.SECONDS);
        }

        private synchronized String asString() {
            return toString(StandardCharsets.UTF_8);
        }
    }
}
