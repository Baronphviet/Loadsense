package com.loadsense.core.service;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Shared across workers: spaces request starts according to the configured load profile instead of bursting per VU. */
public final class PacedRateLimiter {
    private final int targetRps;
    private final String loadProfile;
    private final long rampUpNanos;
    private final long durationNanos;
    private final long startNanos;
    private long next;
    public PacedRateLimiter(int requestsPerSecond) {
        this(requestsPerSecond, "CONSTANT", 0, 0L);
    }
    public PacedRateLimiter(int requestsPerSecond, String loadProfile, int rampUpSeconds, long durationNanos) {
        this.targetRps=Math.max(1,requestsPerSecond);
        this.loadProfile=(loadProfile==null || loadProfile.isBlank()) ? "CONSTANT" : loadProfile;
        this.rampUpNanos=Math.max(0,rampUpSeconds)*1_000_000_000L;
        this.durationNanos=Math.max(0L,durationNanos);
        this.startNanos=System.nanoTime();
        this.next=this.startNanos;
    }
    public boolean acquire(long deadline, BooleanSupplier cancelled) {
        long slot;
        synchronized(this) {
            long now=System.nanoTime();
            slot=Math.max(next,now);
            double rps=effectiveRps(Math.max(0L,slot-startNanos));
            long spacing=Math.max(1_000L,Math.round(1_000_000_000.0/rps));
            next=slot+spacing;
        }
        if(slot>=deadline) return false;
        while(!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            long remaining=slot-System.nanoTime();
            if(remaining<=0) return System.nanoTime()<deadline;
            LockSupport.parkNanos(Math.min(remaining,20_000_000));
        }
        return false;
    }
    public double effectiveRps(long elapsedNanos) {
        switch(loadProfile) {
            case "RAMP_UP" -> {
                if(rampUpNanos<=0 || elapsedNanos>=rampUpNanos) return targetRps;
                double base=Math.max(1.0,targetRps*0.2);
                double progress=Math.min(1.0,elapsedNanos/(double)rampUpNanos);
                return base+(targetRps-base)*progress;
            }
            case "STEP_STRESS" -> {
                if(durationNanos<=0) return targetRps;
                double progress=elapsedNanos/(double)durationNanos;
                double factor=progress<0.25 ? 0.25 : progress<0.50 ? 0.50 : progress<0.75 ? 0.75 : 1.0;
                return Math.max(1.0,targetRps*factor);
            }
            case "SPIKE" -> {
                if(durationNanos<=0) return targetRps;
                double progress=elapsedNanos/(double)durationNanos;
                return (progress>=0.40 && progress<=0.65) ? targetRps : Math.max(1.0,targetRps*0.30);
            }
            default -> {
                return targetRps;
            }
        }
    }
}
