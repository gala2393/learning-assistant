package com.mytext.learningassistant.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 短期状态存储的内存实现 —— 基于 {@link ConcurrentHashMap}，适用于开发环境和单机部署。
 *
 * <h3>自动装配条件</h3>
 * <p>
 * 通过 {@code @ConditionalOnProperty} 控制激活条件：
 * 当配置项 {@code app.redis.enabled} 为 {@code false}（或未配置）时，Spring 会自动创建本类的实例。
 * 反之，当 Redis 开启时，会使用 {@link RedisShortTermStateStore} 代替。
 * </p>
 * <p>
 * {@code matchIfMissing = true} 表示即使配置文件中没有写这一项，也默认使用内存实现（开发友好）。
 * </p>
 *
 * <h3>工作原理</h3>
 * <ul>
 *   <li>每个键值对都附带一个"过期时间戳"（{@code expiresAt}），存储在内部的 {@link Entry} 记录中。</li>
 *   <li>读取时检查是否过期，过期则返回 null 并移除。</li>
 *   <li>定期执行清理（每 5 分钟），避免已过期条目无限堆积占用内存。</li>
 *   <li>计数器递增使用 {@code compute()} 保证线程安全（同一 JVM 内的并发安全）。</li>
 * </ul>
 *
 * <h3>局限性</h3>
 * <p>
 * 本实现只在单个 JVM 内有效。如果应用部署了多个实例（如多个 Docker 容器），
 * 每个实例各自维护独立的内存状态，限流数据不共享。此时应切换到
 * {@link RedisShortTermStateStore}。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryShortTermStateStore implements ShortTermStateStore {

    /** 清理间隔：5 分钟（300,000 毫秒）。每隔这么久才扫描一次过期条目，避免频繁清理浪费性能。 */
    private static final long CLEANUP_INTERVAL_MILLIS = 300_000L;

    /** 核心数据结构：线程安全的 Map，键为业务 key，值为带过期时间的 Entry。 */
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** 时钟对象，用于获取当前时间（方便测试时替换为虚拟时钟）。 */
    private final Clock clock = Clock.systemUTC();

    /** 上次执行清理的时间戳，用 volatile 保证多线程可见性。 */
    private volatile long lastCleanupAt;

    /**
     * 存储键值对并设置过期时间。
     * <p>过期时间最少为 1 毫秒，防止传入 0 或负数导致立即过期。</p>
     *
     * @param key   存储的键
     * @param value 存储的值
     * @param ttl   过期时长
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        long now = clock.millis();
        maybeCleanup(now);
        // 将当前时间 + TTL 转换为绝对过期时间戳存入 Entry
        entries.put(key, new Entry(value, now + Math.max(1L, ttl.toMillis())));
    }

    /**
     * 根据键获取值。
     * <p>如果条目已过期，会主动删除并返回 null（惰性清理策略）。</p>
     *
     * @param key 要查询的键
     * @return 值，不存在或已过期返回 null
     */
    @Override
    public String get(String key) {
        long now = clock.millis();
        maybeCleanup(now);
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        // 检查是否已过期：过期时间戳 <= 当前时间
        if (entry.expiresAt <= now) {
            // 用 remove(key, entry) 而非 remove(key)，避免误删刚被其他线程写入的新值
            entries.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    /**
     * 获取值并立即删除（原子性：读完即删）。
     * <p>
     * 典型场景：一次性验证码校验。读取成功 = 验证码有效，同时删除防止二次使用。
     * </p>
     *
     * @param key 要获取并删除的键
     * @return 值，不存在或已过期返回 null
     */
    @Override
    public String getAndDelete(String key) {
        long now = clock.millis();
        maybeCleanup(now);
        // 直接移除，一步完成"取 + 删"
        Entry entry = entries.remove(key);
        if (entry == null || entry.expiresAt <= now) {
            return null;
        }
        return entry.value;
    }

    /**
     * 删除指定键。
     *
     * @param key 要删除的键
     */
    @Override
    public void delete(String key) {
        entries.remove(key);
    }

    /**
     * 计数器递增并返回最新值（用于限流）。
     * <p>
     * 使用 {@code compute()} 保证原子性：在同一 JVM 内，即使多个线程同时对同一 key 调用，
     * 也不会出现计数丢失的问题。
     * </p>
     * <p>如果键不存在或已过期，会创建新条目，初始值为 1，并重新设置 TTL。</p>
     * <p>如果键存在且未过期，在原有值上加 1，保持原有过期时间不变。</p>
     *
     * @param key 计数器的键
     * @param ttl 首次创建时的过期时间
     * @return 递增后的计数值
     */
    @Override
    public long incrementAndGet(String key, Duration ttl) {
        long now = clock.millis();
        // compute 是 ConcurrentHashMap 的原子操作，lambda 内部的读写对其他线程互斥
        Entry entry = entries.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt <= now) {
                // 键不存在或已过期 → 创建新条目，计数为 1，设置新的过期时间
                return new Entry("1", now + Math.max(1L, ttl.toMillis()));
            }
            // 键存在且未过期 → 计数加 1，保持原有 expiresAt 不变
            long count = parseLong(current.value) + 1L;
            return new Entry(Long.toString(count), current.expiresAt);
        });
        return parseLong(entry.value);
    }

    /**
     * 安全地将字符串解析为 long。
     * <p>如果解析失败（如数据损坏），返回 0 而非抛异常，增强容错性。</p>
     *
     * @param value 要解析的字符串
     * @return 解析后的 long 值，解析失败返回 0
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /**
     * 惰性清理：检查是否到了该清理的时间，如果是则遍历并移除所有过期条目。
     * <p>
     * 为什么用"惰性清理"而不是启动定时器？
     * 因为惰性清理实现简单，不需要额外的线程，而且在请求不频繁时不会浪费资源。
     * </p>
     *
     * @param now 当前时间戳（毫秒）
     */
    private void maybeCleanup(long now) {
        // 距离上次清理不到 5 分钟，直接跳过
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MILLIS) {
            return;
        }
        lastCleanupAt = now;
        // 遍历所有条目，移除已过期的
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt <= now) {
                iterator.remove();
            }
        }
    }

    /**
     * 内部存储条目，使用 Java record（不可变数据类）。
     *
     * @param value     存储的值
     * @param expiresAt 绝对过期时间戳（毫秒），到达此时间后条目视为过期
     */
    private record Entry(String value, long expiresAt) {
    }
}
