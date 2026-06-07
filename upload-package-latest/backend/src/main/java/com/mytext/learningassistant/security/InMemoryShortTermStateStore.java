package com.mytext.learningassistant.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryShortTermStateStore implements ShortTermStateStore {

    private static final long CLEANUP_INTERVAL_MILLIS = 300_000L;

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();
    private volatile long lastCleanupAt;

    @Override
    public void put(String key, String value, Duration ttl) {
        long now = clock.millis();
        maybeCleanup(now);
        entries.put(key, new Entry(value, now + Math.max(1L, ttl.toMillis())));
    }

    @Override
    public String get(String key) {
        long now = clock.millis();
        maybeCleanup(now);
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt <= now) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    @Override
    public String getAndDelete(String key) {
        long now = clock.millis();
        maybeCleanup(now);
        Entry entry = entries.remove(key);
        if (entry == null || entry.expiresAt <= now) {
            return null;
        }
        return entry.value;
    }

    @Override
    public void delete(String key) {
        entries.remove(key);
    }

    @Override
    public long incrementAndGet(String key, Duration ttl) {
        long now = clock.millis();
        Entry entry = entries.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt <= now) {
                return new Entry("1", now + Math.max(1L, ttl.toMillis()));
            }
            long count = parseLong(current.value) + 1L;
            return new Entry(Long.toString(count), current.expiresAt);
        });
        return parseLong(entry.value);
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private void maybeCleanup(long now) {
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MILLIS) {
            return;
        }
        lastCleanupAt = now;
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt <= now) {
                iterator.remove();
            }
        }
    }

    private record Entry(String value, long expiresAt) {
    }
}
