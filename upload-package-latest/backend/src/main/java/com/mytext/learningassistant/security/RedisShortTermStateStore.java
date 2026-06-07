package com.mytext.learningassistant.security;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
public class RedisShortTermStateStore implements ShortTermStateStore {

    private final StringRedisTemplate redisTemplate;

    public RedisShortTermStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public String getAndDelete(String key) {
        return redisTemplate.opsForValue().getAndDelete(key);
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public long incrementAndGet(String key, Duration ttl) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return count == null ? 0L : count;
    }
}
