package com.mytext.learningassistant.security;

import java.time.Duration;
import java.util.Locale;

import com.mytext.learningassistant.common.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private static final Duration WINDOW_TTL = Duration.ofSeconds(75);

    private final ShortTermStateStore stateStore;
    private final int loginPerMinute;
    private final int registerPerMinute;
    private final int emailCodePerMinute;
    private final int llmConfigTestPerMinute;
    private final int ragChatPerMinute;

    public RateLimitService(
        ShortTermStateStore stateStore,
        @Value("${app.security.rate-limit.login-per-minute:10}") int loginPerMinute,
        @Value("${app.security.rate-limit.register-per-minute:10}") int registerPerMinute,
        @Value("${app.security.rate-limit.email-code-per-minute:6}") int emailCodePerMinute,
        @Value("${app.security.rate-limit.llm-config-test-per-minute:5}") int llmConfigTestPerMinute,
        @Value("${app.security.rate-limit.rag-chat-per-minute:30}") int ragChatPerMinute
    ) {
        this.stateStore = stateStore;
        this.loginPerMinute = loginPerMinute;
        this.registerPerMinute = registerPerMinute;
        this.emailCodePerMinute = emailCodePerMinute;
        this.llmConfigTestPerMinute = llmConfigTestPerMinute;
        this.ragChatPerMinute = ragChatPerMinute;
    }

    public void checkLogin(String identity) {
        check("login", identity, loginPerMinute);
    }

    public void checkRegister(String identity) {
        check("register", identity, registerPerMinute);
    }

    public void checkEmailCode(String identity) {
        check("email-code", identity, emailCodePerMinute);
    }

    public void checkLlmConfigTest(String identity) {
        check("llm-config-test", identity, llmConfigTestPerMinute);
    }

    public void checkRagChat(String identity) {
        check("rag-chat", identity, ragChatPerMinute);
    }

    private void check(String bucket, String identity, int limit) {
        if (limit <= 0) {
            return;
        }
        long count = stateStore.incrementAndGet("rate:" + bucket + ":" + normalize(identity), WINDOW_TTL);
        if (count > limit) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
    }

    private String normalize(String identity) {
        String normalized = identity == null ? "" : identity.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "anonymous" : normalized;
    }
}
