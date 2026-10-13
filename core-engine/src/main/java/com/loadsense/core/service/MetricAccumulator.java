package com.loadsense.core.service;

import com.loadsense.core.entity.TestMetric;
import java.time.Instant;
import java.util.UUID;

/** Fixed-size histogram: 1 ms buckets. Memory does not grow with request count. */
public class MetricAccumulator {
    private final long[] histogram=new long[30001];
    private long total, failed, latencyNanos, previousTotal;
    private double previousElapsed;
    public synchronized void record(long nanos, boolean error) {
        total++; if(error) failed++; latencyNanos+=nanos;
        histogram[(int)Math.min(30000,Math.max(0,Math.round(nanos/1_000_000.0)))]++;
    }
    public synchronized TestMetric sample(UUID executionId,double elapsed,int activeUsers) {
        var m=new TestMetric(); m.executionId=executionId; m.sampleTime=Instant.now();
        m.elapsedSeconds=elapsed; m.totalRequests=total; m.failedRequests=failed;
        m.throughput=(total-previousTotal)/Math.max(0.001,elapsed-previousElapsed);
        m.avgLatencyMs=total==0 ? 0 : latencyNanos/1_000_000.0/total;
        m.p50LatencyMs=percentile(0.50); m.p95LatencyMs=percentile(0.95);
        m.errorRate=total==0 ? 0 : failed*100.0/total; m.activeUsers=activeUsers;
        previousElapsed=elapsed; previousTotal=total; return m;
    }
    private int percentile(double fraction) {
        if(total==0) return 0;
        long target=(long)Math.ceil(total*fraction), cumulative=0;
        for(int i=0;i<histogram.length;i++) { cumulative+=histogram[i]; if(cumulative>=target) return i; }
        return 30000;
    }
}
