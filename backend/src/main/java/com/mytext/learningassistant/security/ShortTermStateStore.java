package com.mytext.learningassistant.security;

import java.time.Duration;

/**
 * 短期状态存储接口 —— 统一抽象验证码、限流计数器等临时数据的存取操作。
 *
 * <h3>设计模式：策略模式（Strategy Pattern）</h3>
 * <p>
 * 本接口定义了"存什么、取什么、怎么删"的标准操作，但不关心底层用什么技术实现。
 * 具体的存储介质由两个实现类决定：
 * </p>
 * <ul>
 *   <li>{@link InMemoryShortTermStateStore} —— 基于内存（ConcurrentHashMap），适合开发环境和单机部署</li>
 *   <li>{@link RedisShortTermStateStore} —— 基于 Redis，适合生产环境和多实例部署</li>
 * </ul>
 * <p>
 * 通过 Spring 的 {@code @ConditionalOnProperty} 注解，系统会根据配置文件中
 * {@code app.redis.enabled} 的值自动选择其中一个实现，业务代码无需关心底层细节。
 * 这就是"面向接口编程"的好处：调用方只依赖这个接口，具体实现可以随时替换。
 * </p>
 *
 * <h3>典型使用场景</h3>
 * <ul>
 *   <li>存储邮箱验证码（带过期时间）</li>
 *   <li>存储接口限流计数器（递增 + 自动过期）</li>
 *   <li>存储一次性令牌（读取后立即删除）</li>
 * </ul>
 */
public interface ShortTermStateStore {

    /**
     * 存储一个键值对，并设置过期时间。
     *
     * @param key   存储的键（如 "email-code:user@example.com"）
     * @param value 存储的值（如验证码 "123456"）
     * @param ttl   过期时间（Time To Live），超过这个时间后数据自动失效
     */
    void put(String key, String value, Duration ttl);

    /**
     * 根据键获取对应的值。
     * <p>如果键不存在或已过期，返回 null。</p>
     *
     * @param key 要查询的键
     * @return 对应的值，不存在或已过期时返回 null
     */
    String get(String key);

    /**
     * 获取值并立即删除该键（原子操作）。
     * <p>
     * 常用于一次性令牌的校验：读取成功说明令牌有效，同时自动删除防止重复使用。
     * </p>
     *
     * @param key 要获取并删除的键
     * @return 对应的值，不存在或已过期时返回 null
     */
    String getAndDelete(String key);

    /**
     * 根据键删除对应的条目。
     *
     * @param key 要删除的键
     */
    void delete(String key);

    /**
     * 将指定键的计数器加 1，并返回递增后的值。
     * <p>
     * 如果该键不存在或已过期，会自动创建并设置初始值为 1，同时应用给定的过期时间。
     * 这是实现滑动窗口限流的核心方法。
     * </p>
     *
     * @param key 要递增的计数器键（如 "rate:login:192.168.1.1"）
     * @param ttl 计数器的过期时间（窗口大小），首次创建时生效
     * @return 递增后的计数值
     */
    long incrementAndGet(String key, Duration ttl);
}
