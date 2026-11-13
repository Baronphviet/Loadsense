package com.loadsense.core.service;

import com.loadsense.core.entity.TestMetric;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Fixed-size cumulative and per-interval histograms: 1 ms buckets. Memory does not grow with request count. */
public class MetricAccumulator {
    private final long[] histogram=new long[30001];
    private final long[] windowHistogram=new long[30001];
    private final Map<String, Long> breakdown=new LinkedHashMap<>();
    private long total, failed, latencyNanos, previousTotal, maxNanos;
    private long windowTotal, windowFailed;
    private double previousElapsed;
    public synchronized void record(long nanos, boolean error) {
        record(nanos, error ? "network" : null);
    }
    public synchronized void record(long nanos, String errorCategory) {
        total++;
        windowTotal++;
        if(errorCategory != null) {
            failed++;
            windowFailed++;
            breakdown.merge(errorCategory, 1L, Long::sum);
        }
        latencyNanos+=nanos;
        if(nanos>maxNanos) maxNanos=nanos;
        int bucket=(int)Math.min(30000,Math.max(0,Math.round(nanos/1_000_000.0)));
        histogram[bucket]++;
        windowHistogram[bucket]++;
    }
    public synchronized Map<String, Long> errorBreakdown() {
        return Map.copyOf(breakdown);
    }
    public synchronized TestMetric sample(UUID executionId,double elapsed,int activeUsers) {
        var m=new TestMetric(); m.executionId=executionId; m.sampleTime=Instant.now();
        m.elapsedSeconds=elapsed; m.totalRequests=total; m.failedRequests=failed;
        m.throughput=(total-previousTotal)/Math.max(0.001,elapsed-previousElapsed);
        m.avgLatencyMs=total==0 ? 0 : latencyNanos/1_000_000.0/total;
        m.p50LatencyMs=percentile(histogram,total,0.50);
        m.p95LatencyMs=percentile(histogram,total,0.95);
        m.p99LatencyMs=percentile(histogram,total,0.99);
        m.maxLatencyMs=total==0 ? 0 : maxNanos/1_000_000.0;
        m.errorRate=total==0 ? 0 : failed*100.0/total;
        m.intervalP95Ms=windowTotal==0 ? 0 : percentile(windowHistogram,windowTotal,0.95);
        m.intervalErrorRate=windowTotal==0 ? 0 : windowFailed*100.0/windowTotal;
        m.activeUsers=activeUsers;
        previousElapsed=elapsed; previousTotal=total;
        Arrays.fill(windowHistogram,0L); windowTotal=0; windowFailed=0;
        return m;
    }
    private static int percentile(long[] hist,long count,double fraction) {
        if(count==0) return 0;
        long target=(long)Math.ceil(count*fraction), cumulative=0;
        for(int i=0;i<hist.length;i++) { cumulative+=hist[i]; if(cumulative>=target) return i; }
        return 30000;
    }
}
