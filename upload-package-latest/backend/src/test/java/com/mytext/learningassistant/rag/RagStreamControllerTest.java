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
import com.mytext.learningassistant.security.RateLimitService;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * RAG 流式聊天控制器单元测试。
 * <p>
 * 覆盖范围：SSE（Server-Sent Events）流式响应的行为验证，
 * 包括分块事件的实时推送顺序、大文本 delta 的自动分片机制。
 * <p>
 * 使用 Mockito 模拟 RagService，通过 ChunkDetectingOutputStream
 * 检测输出流中分块事件的时序，不依赖真实 LLM 服务。
 */
class RagStreamControllerTest {

    /**
     * 测试场景：流式聊天响应中，chunk 事件在 service 返回前就已写入输出流。
     * <p>
     * 预期结果：
     * <ul>
     *   <li>chunk 事件在 service 方法返回前即可见到输出流（chunkVisibleBeforeServiceReturned=true）</li>
     *   <li>SSE 流包含 status、chunk、done 事件</li>
     *   <li>chunk 事件包含正确的 delta 文本</li>
     *   <li>chunk 事件在 done 事件之前</li>
     * </ul>
     */
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
            new ObjectMapper(),
            mock(RateLimitService.class)
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
            null,
            "STUDY",
            List.of(),
            null
        );
        ResponseEntity<StreamingResponseBody> response = controller.chatStream(1L, request, mock(HttpServletRequest.class));

        response.getBody().writeTo(output);

        String body = output.asString();
        assertThat(chunkVisibleBeforeServiceReturned).isTrue();
        assertThat(body).contains("event: status");
        assertThat(body).contains("event: chunk");
        assertThat(body).contains("\"delta\":\"Hello \"");
        assertThat(body).contains("event: done");
        assertThat(body.indexOf("event: chunk")).isLessThan(body.indexOf("event: done"));
    }

    /**
     * 测试场景：LLM 提供方返回较大的 delta 文本时，控制器自动拆分为多个 SSE chunk 事件。
     * <p>
     * 预期结果：输出中包含 3 个以上的 chunk 事件（大文本被分片），
     *           每个 chunk 包含 delta 的一部分，chunk 事件在 done 事件之前。
     */
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
            new ObjectMapper(),
            mock(RateLimitService.class)
        );

        ResponseEntity<StreamingResponseBody> response = controller.chatStream(1L, streamRequest(), mock(HttpServletRequest.class));
        var output = new ChunkDetectingOutputStream();

        response.getBody().writeTo(output);

        String body = output.asString();
        assertThat(body.split("event: chunk", -1).length - 1).isGreaterThan(3);
        assertThat(body).contains("\"delta\":\"This is a \"");
        assertThat(body.indexOf("event: chunk")).isLessThan(body.indexOf("event: done"));
    }

    /** 构造通用流式聊天请求对象 */
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
            null,
            "STUDY",
            List.of(),
            null
        );
    }

    /**
     * 带分块检测功能的输出流。
     * <p>
     * 继承 ByteArrayOutputStream，在每次写入时检查是否已包含 "event: chunk"，
     * 并通过 CountDownLatch 通知等待方分块数据已到达。
     */
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
