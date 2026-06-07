package com.mytext.learningassistant.security;

import java.time.Duration;
import java.util.Locale;

import com.mytext.learningassistant.common.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 接口限流服务 —— 防止单个用户/IP 短时间内发送过多请求，保护系统免受恶意刷接口和暴力破解。
 *
 * <h3>限流原理：滑动窗口计数器（近似）</h3>
 * <p>
 * 本服务采用"固定窗口 + TTL 自动过期"的方式近似实现滑动窗口限流：
 * </p>
 * <ol>
 *   <li>每个限流维度（如某个 IP 的登录请求）对应一个计数器键，格式如 {@code rate:login:192.168.1.1}</li>
 *   <li>每次请求到来，计数器加 1（通过 {@link ShortTermStateStore#incrementAndGet} 实现）</li>
 *   <li>计数器的 TTL 设为 75 秒（略大于 60 秒，避免窗口边界处的计数丢失问题）</li>
 *   <li>如果计数超过阈值（如每分钟 10 次），抛出 429 异常拒绝请求</li>
 *   <li>TTL 到期后计数器自动清零，重新开始计数</li>
 * </ol>
 *
 * <h3>支持的限流场景</h3>
 * <ul>
 *   <li>登录接口 —— 防止暴力破解密码</li>
 *   <li>注册接口 —— 防止恶意批量注册</li>
 *   <li>邮箱验证码接口 —— 防止验证码轰炸</li>
 *   <li>LLM 配置测试接口 —— 防止滥用 AI 资源</li>
 *   <li>RAG 问答接口 —— 防止过度消耗 AI 和向量检索资源</li>
 * </ul>
 *
 * <h3>配置方式</h3>
 * <p>各接口的每分钟限制次数可通过 {@code application.yml} 配置：</p>
 * <pre>
 * app.security.rate-limit.login-per-minute: 10
 * app.security.rate-limit.register-per-minute: 10
 * app.security.rate-limit.email-code-per-minute: 6
 * app.security.rate-limit.llm-config-test-per-minute: 5
 * app.security.rate-limit.rag-chat-per-minute: 30
 * </pre>
 * <p>设置为 0 或负数表示不限制。</p>
 */
@Service
public class RateLimitService {

    /**
     * 窗口 TTL：75 秒。
     * <p>
     * 为什么是 75 秒而不是 60 秒？
     * 如果正好设为 60 秒，在第 59 秒时发了一个请求，TTL 到第 60 秒就过期了，
     * 然后下一秒（第 61 秒）又可以重新计数，实际上相当于 2 秒内发了两次。
     * 多出的 15 秒作为"缓冲区"，让窗口边界处的限流更平滑。
     * </p>
     */
    private static final Duration WINDOW_TTL = Duration.ofSeconds(75);

    /** 底层状态存储（内存或 Redis，由配置决定）。 */
    private final ShortTermStateStore stateStore;

    // ---- 各接口的每分钟限流阈值 ----
    private final int loginPerMinute;
    private final int registerPerMinute;
    private final int emailCodePerMinute;
    private final int llmConfigTestPerMinute;
    private final int ragChatPerMinute;

    /**
     * 构造函数，通过 Spring 注入状态存储和配置值。
     *
     * @param stateStore               短期状态存储（自动选择内存或 Redis 实现）
     * @param loginPerMinute           登录接口每分钟限制次数（默认 10）
     * @param registerPerMinute        注册接口每分钟限制次数（默认 10）
     * @param emailCodePerMinute       邮箱验证码接口每分钟限制次数（默认 6）
     * @param llmConfigTestPerMinute   LLM 配置测试接口每分钟限制次数（默认 5）
     * @param ragChatPerMinute         RAG 问答接口每分钟限制次数（默认 30）
     */
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

    /**
     * 检查登录接口是否超出限流。
     *
     * @param identity 用户标识（通常是 IP 地址或用户名）
     * @throws BusinessException 超出限制时抛出 429 异常
     */
    public void checkLogin(String identity) {
        check("login", identity, loginPerMinute);
    }

    /**
     * 检查注册接口是否超出限流。
     *
     * @param identity 用户标识
     * @throws BusinessException 超出限制时抛出 429 异常
     */
    public void checkRegister(String identity) {
        check("register", identity, registerPerMinute);
    }

    /**
     * 检查邮箱验证码接口是否超出限流。
     *
     * @param identity 用户标识（通常是邮箱地址）
     * @throws BusinessException 超出限制时抛出 429 异常
     */
    public void checkEmailCode(String identity) {
        check("email-code", identity, emailCodePerMinute);
    }

    /**
     * 检查 LLM 配置测试接口是否超出限流。
     *
     * @param identity 用户标识
     * @throws BusinessException 超出限制时抛出 429 异常
     */
    public void checkLlmConfigTest(String identity) {
        check("llm-config-test", identity, llmConfigTestPerMinute);
    }

    /**
     * 检查 RAG 问答接口是否超出限流。
     *
     * @param identity 用户标识
     * @throws BusinessException 超出限制时抛出 429 异常
     */
    public void checkRagChat(String identity) {
        check("rag-chat", identity, ragChatPerMinute);
    }

    /**
     * 限流核心逻辑。
     * <p>
     * 工作流程：
     * 1. 拼接限流键：{@code rate:bucket:identity}（如 "rate:login:192.168.1.1"）
     * 2. 调用存储的递增方法，计数器加 1
     * 3. 如果计数超过阈值，抛出业务异常（HTTP 429）
     * </p>
     *
     * @param bucket   限流桶名称（区分不同接口）
     * @param identity 用户标识（区分不同用户/IP）
     * @param limit    每窗口允许的最大请求数
     * @throws BusinessException 超出限制时抛出
     */
    private void check(String bucket, String identity, int limit) {
        // limit <= 0 表示不限流，直接放行
        if (limit <= 0) {
            return;
        }
        // 构建键名并递增计数，TTL 到期自动归零
        long count = stateStore.incrementAndGet("rate:" + bucket + ":" + normalize(identity), WINDOW_TTL);
        if (count > limit) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
    }

    /**
     * 标准化用户标识：去除首尾空白、转小写、空值兜底为 "anonymous"。
     * <p>
     * 这样做是为了避免同一个用户通过不同大小写绕过限流。
     * 例如 "Admin" 和 "admin" 应被视为同一个用户。
     * </p>
     *
     * @param identity 原始用户标识
     * @return 标准化后的标识
     */
    private String normalize(String identity) {
        String normalized = identity == null ? "" : identity.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "anonymous" : normalized;
    }
}
