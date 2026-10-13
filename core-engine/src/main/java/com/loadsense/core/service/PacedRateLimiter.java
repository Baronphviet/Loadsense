package com.loadsense.core.service;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Shared across workers: spaces request starts instead of allowing a burst per VU. */
public final class PacedRateLimiter {
    private final long spacing;
    private long next;
    public PacedRateLimiter(int requestsPerSecond) { spacing=1_000_000_000L/requestsPerSecond; next=System.nanoTime(); }
    public boolean acquire(long deadline, BooleanSupplier cancelled) {
        long slot;
        synchronized(this) { long now=System.nanoTime(); slot=Math.max(next,now); next=slot+spacing; }
        if(slot>=deadline) return false;
        while(!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            long remaining=slot-System.nanoTime();
            if(remaining<=0) return System.nanoTime()<deadline;
            LockSupport.parkNanos(Math.min(remaining,20_000_000));
        }
        return false;
    }
}
