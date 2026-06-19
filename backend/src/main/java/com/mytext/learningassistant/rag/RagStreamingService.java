package com.mytext.learningassistant.rag;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

/**
 * RAG 流式输出辅助服务。
 *
 * <p>负责普通回答长度上限、流式增量截断和非流式结果的小块推送。模型调用和问答持久化仍留在 RagService，
 * 后续再逐步迁移到完整的流式编排服务。</p>
 */
@Service
class RagStreamingService {

    static final int MAX_SINGLE_ANSWER_CHARS = 12_000;

    static final String ANSWER_LIMIT_CONTINUE_NOTICE =
        "\n\n[本次回答已达到 12000 字上限。可以点击“继续生成”或输入“继续”，系统会接着生成。]";

    String limitAnswerText(String answer) {
        if (answer == null || answer.length() <= MAX_SINGLE_ANSWER_CHARS) {
            return answer == null ? "" : answer;
        }
        return answer.substring(0, MAX_SINGLE_ANSWER_CHARS).trim() + ANSWER_LIMIT_CONTINUE_NOTICE;
    }

    String limitStreamDelta(
        String delta,
        boolean longDocumentAnswer,
        AtomicInteger streamedAnswerChars,
        AtomicBoolean streamedLimitNotice
    ) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        if (longDocumentAnswer) {
            return delta;
        }
        int emitted = streamedAnswerChars.get();
        if (emitted >= MAX_SINGLE_ANSWER_CHARS) {
            if (streamedLimitNotice.compareAndSet(false, true)) {
                return ANSWER_LIMIT_CONTINUE_NOTICE;
            }
            return "";
        }
        int remaining = MAX_SINGLE_ANSWER_CHARS - emitted;
        if (delta.length() <= remaining) {
            streamedAnswerChars.addAndGet(delta.length());
            return delta;
        }
        streamedAnswerChars.set(MAX_SINGLE_ANSWER_CHARS);
        String visible = delta.substring(0, remaining).trim();
        if (streamedLimitNotice.compareAndSet(false, true)) {
            return visible + ANSWER_LIMIT_CONTINUE_NOTICE;
        }
        return visible;
    }

    void streamAnswerInSmallChunks(String answer, Consumer<String> onChunk) {
        String value = answer == null ? "" : answer;
        if (value.isBlank()) {
            return;
        }
        int index = 0;
        while (index < value.length()) {
            int next = nextStreamChunkEnd(value, index);
            onChunk.accept(value.substring(index, next));
            index = next;
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private int nextStreamChunkEnd(String value, int start) {
        int maxEnd = Math.min(value.length(), start + 18);
        for (int i = start + 1; i <= maxEnd; i++) {
            char c = value.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '，' || c == '；' || c == '：' || c == '！'
                || c == '.' || c == ',' || c == ';') {
                return i;
            }
        }
        return maxEnd;
    }
}
