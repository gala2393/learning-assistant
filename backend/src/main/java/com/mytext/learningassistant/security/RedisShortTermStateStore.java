package com.mytext.learningassistant.security;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 短期状态存储的 Redis 实现 —— 适用于生产环境和多实例部署。
 *
 * <h3>自动装配条件</h3>
 * <p>
 * 通过 {@code @ConditionalOnProperty} 控制：仅当配置项 {@code app.redis.enabled} 为 {@code true} 时才激活。
 * 与 {@link InMemoryShortTermStateStore} 互斥，二者不会同时存在。
 * </p>
 *
 * <h3>相比内存实现的分布式优势</h3>
 * <ul>
 *   <li><b>状态共享</b>：所有应用实例连接同一个 Redis，限流计数器和验证码数据全局共享。
 *       例如，即使用户对实例 A 发了 5 次请求、对实例 B 发了 5 次请求，限流也能正确统计为 10 次。</li>
 *   <li><b>原子操作</b>：Redis 的 INCR 命令是原子的（单线程模型保证），天然适合并发计数场景，
 *       不需要像内存实现那样依赖 JVM 级别的锁。</li>
 *   <li><b>自动过期</b>：Redis 原生支持 TTL（EXPIRE 命令），键到期后由 Redis 自动清理，
 *       不需要像内存实现那样手动做"惰性清理"。</li>
 *   <li><b>持久化</b>：可配置 RDB/AOF 持久化，服务重启后数据不会丢失（对验证码场景意义不大，
 *       但对于某些需要持久计数的场景有用）。</li>
 * </ul>
 *
 * <h3>前置条件</h3>
 * <p>
 * 项目中必须引入 {@code spring-boot-starter-data-redis} 依赖，
 * 并在 {@code application.yml} 中配置好 Redis 连接地址，否则 {@link StringRedisTemplate} 无法注入。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
public class RedisShortTermStateStore implements ShortTermStateStore {

    /** Spring Data Redis 提供的字符串模板，封装了 Redis 的常用操作。 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 构造函数，由 Spring 自动注入 {@link StringRedisTemplate}。
     *
     * @param redisTemplate Redis 字符串操作模板
     */
    public RedisShortTermStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 存储键值对并设置过期时间。
     * <p>底层执行 Redis 命令：{@code SET key value PX ttl毫秒}（或 EX 秒）。</p>
     *
     * @param key   存储的键
     * @param value 存储的值
     * @param ttl   过期时长
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    /**
     * 根据键获取值。
     * <p>底层执行 Redis 命令：{@code GET key}。键不存在返回 null。</p>
     *
     * @param key 要查询的键
     * @return 值，不存在返回 null
     */
    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 获取值并立即删除（原子操作）。
     * <p>
     * 底层执行 Redis 命令：{@code GETDEL key}（Redis 6.2+）。
     * Spring Data Redis 会根据 Redis 版本自动选择合适的实现方式。
     * </p>
     *
     * @param key 要获取并删除的键
     * @return 值，不存在返回 null
     */
    @Override
    public String getAndDelete(String key) {
        return redisTemplate.opsForValue().getAndDelete(key);
    }

    /**
     * 删除指定键。
     * <p>底层执行 Redis 命令：{@code DEL key}。</p>
     *
     * @param key 要删除的键
     */
    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 计数器递增并返回最新值。
     * <p>
     * <b>关键实现细节（为什么要判断 count == 1）：</b>
     * </p>
     * <p>
     * Redis 的 {@code INCR key} 命令在键不存在时会自动创建并设为 0 再加 1（即返回 1），
     * 但不会自动设置过期时间。所以我们在首次递增时（返回值为 1），手动调用 {@code EXPIRE} 设置 TTL。
     * 后续的递增操作保持原有 TTL 不变，避免每次递增都重置过期时间。
     * </p>
     * <p>
     * 注意：{@code INCR} 和 {@code EXPIRE} 之间存在极小的时间窗口（毫秒级），
     * 但在实际业务中，这个时间差对限流的准确性影响可以忽略不计。
     * 如需完全原子，可使用 Lua 脚本，但会增加复杂度。
     * </p>
     *
     * @param key 计数器的键
     * @param ttl 首次创建时的过期时间
     * @return 递增后的计数值
     */
    @Override
    public long incrementAndGet(String key, Duration ttl) {
        // Redis INCR 是原子操作：即使多个实例同时调用，计数也不会出错
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // count == 1 说明这个键是刚创建的（之前不存在或已过期），需要设置过期时间
            redisTemplate.expire(key, ttl);
        }
        return count == null ? 0L : count;
    }
}
