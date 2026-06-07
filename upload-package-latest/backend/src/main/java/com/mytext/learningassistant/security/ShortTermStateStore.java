package com.mytext.learningassistant.security;

import java.time.Duration;

public interface ShortTermStateStore {

    void put(String key, String value, Duration ttl);

    String get(String key);

    String getAndDelete(String key);

    void delete(String key);

    long incrementAndGet(String key, Duration ttl);
}
