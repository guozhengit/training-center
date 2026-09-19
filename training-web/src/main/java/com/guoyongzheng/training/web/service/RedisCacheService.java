package com.guoyongzheng.training.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Thin Redis cache wrapper. Only created when {@code training.redis-host} is
 * configured. Every operation degrades gracefully when Redis is unavailable so
 * the application keeps working without caching.
 */
@Component
@ConditionalOnProperty(prefix = "training", name = "redis-host")
public class RedisCacheService {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** Reads a JSON value from Redis; empty when missing or on any Redis failure. */
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** Writes a JSON value with TTL; silently skipped when Redis is unavailable. */
    public void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ignored) {
            // Redis unavailable: degrade gracefully.
        }
    }

    /** Live connectivity check. */
    public boolean ping() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception exception) {
            return false;
        }
    }
}
