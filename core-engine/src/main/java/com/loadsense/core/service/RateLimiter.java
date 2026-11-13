package com.loadsense.core.service;

import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe per-key sliding-window rate limiter. */
public final class RateLimiter {
    private final int maxRequests;
    private final long windowMillis;
    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowSeconds) {
        this.maxRequests = Math.max(1, maxRequests);
        this.windowMillis = Math.max(1L, windowSeconds) * 1000L;
    }

    public boolean tryAcquire(String key) {
        long now = Instant.now().toEpochMilli();
        Deque<Long> deque = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, now);
            if (deque.size() >= maxRequests) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    public boolean isLimited(String key) {
        long now = Instant.now().toEpochMilli();
        Deque<Long> deque = buckets.get(key);
        if (deque == null) return false;
        synchronized (deque) {
            prune(deque, now);
            return deque.size() >= maxRequests;
        }
    }

    public void checkLimitedOnly(String key, String code, String message) {
        if (isLimited(key)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
        }
    }

    public void checkOrThrow(String key, String code, String message) {
        if (!tryAcquire(key)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
        }
    }

    public void reset(String key) {
        buckets.remove(key);
    }

    private void prune(Deque<Long> deque, long now) {
        long cutoff = now - windowMillis;
        while (!deque.isEmpty() && deque.peekFirst() <= cutoff) {
            deque.pollFirst();
        }
    }
}
