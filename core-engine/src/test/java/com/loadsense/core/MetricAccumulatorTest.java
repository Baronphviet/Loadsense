package com.loadsense.core;
import com.loadsense.core.service.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class MetricAccumulatorTest {
    @Test void computesPercentilesErrorsAndIntervalThroughput() {
        var accumulator=new MetricAccumulator(); var id=UUID.randomUUID();
        for(int i=1;i<=100;i++) accumulator.record(i*1_000_000L,i>95);
        var first=accumulator.sample(id,1,10);
        assertThat(first.totalRequests).isEqualTo(100); assertThat(first.failedRequests).isEqualTo(5);
        assertThat(first.avgLatencyMs).isEqualTo(50.5); assertThat(first.p50LatencyMs).isEqualTo(50);
        assertThat(first.p95LatencyMs).isEqualTo(95); assertThat(first.p99LatencyMs).isEqualTo(99);
        assertThat(first.maxLatencyMs).isEqualTo(100.0);
        assertThat(first.intervalP95Ms).isEqualTo(95); assertThat(first.intervalErrorRate).isEqualTo(5);
        assertThat(first.errorRate).isEqualTo(5); assertThat(first.throughput).isEqualTo(100);
        accumulator.record(200_000_000L,false);
        var second=accumulator.sample(id,2,0);
        assertThat(second.throughput).isEqualTo(1);
        assertThat(second.maxLatencyMs).isEqualTo(200.0);
        assertThat(second.intervalP95Ms).isEqualTo(200);
        assertThat(second.intervalErrorRate).isZero();
    }
    @Test void computesDynamicRpsForLoadProfiles() {
        long tenSec=10_000_000_000L;
        var ramp=new PacedRateLimiter(100,"RAMP_UP",10,tenSec);
        assertThat(ramp.effectiveRps(0)).isEqualTo(20.0);
        assertThat(ramp.effectiveRps(5_000_000_000L)).isEqualTo(60.0);
        assertThat(ramp.effectiveRps(10_000_000_000L)).isEqualTo(100.0);

        var stress=new PacedRateLimiter(100,"STEP_STRESS",0,tenSec);
        assertThat(stress.effectiveRps(1_000_000_000L)).isEqualTo(25.0);
        assertThat(stress.effectiveRps(3_000_000_000L)).isEqualTo(50.0);
        assertThat(stress.effectiveRps(6_000_000_000L)).isEqualTo(75.0);
        assertThat(stress.effectiveRps(8_000_000_000L)).isEqualTo(100.0);

        var spike=new PacedRateLimiter(100,"SPIKE",0,tenSec);
        assertThat(spike.effectiveRps(1_000_000_000L)).isEqualTo(30.0);
        assertThat(spike.effectiveRps(5_000_000_000L)).isEqualTo(100.0);
        assertThat(spike.effectiveRps(8_000_000_000L)).isEqualTo(30.0);
    }
    @Test void tracksErrorBreakdownByCategory() {
        var accumulator=new MetricAccumulator();
        accumulator.record(10_000_000L, (String) null);
        accumulator.record(15_000_000L, "http429");
        accumulator.record(20_000_000L, "http5xx");
        accumulator.record(25_000_000L, "http5xx");
        accumulator.record(30_000_000L, "timeout");
        var sample=accumulator.sample(UUID.randomUUID(), 1, 1);
        assertThat(sample.totalRequests).isEqualTo(5);
        assertThat(sample.failedRequests).isEqualTo(4);
        assertThat(accumulator.errorBreakdown())
            .containsEntry("http429", 1L)
            .containsEntry("http5xx", 2L)
            .containsEntry("timeout", 1L)
            .doesNotContainKey("network");
    }
    @Test void sharedRateLimitIsPacedAcrossWorkers() throws Exception {
        var limiter=new PacedRateLimiter(10); long start=System.nanoTime(); long deadline=start+2_000_000_000L;
        var pool=Executors.newFixedThreadPool(10);
        try {
            var tasks=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<10;i++) tasks.add(pool.submit(() -> limiter.acquire(deadline,() -> false)));
            for(var task:tasks) assertThat(task.get(3,TimeUnit.SECONDS)).isTrue();
            assertThat((System.nanoTime()-start)/1_000_000.0).isGreaterThanOrEqualTo(850);
        } finally { pool.shutdownNow(); }
    }
    @Test void cancellationStopsQueuedRateSlots() {
        var limiter=new PacedRateLimiter(1); var cancelled=new AtomicBoolean(true);
        assertThat(limiter.acquire(System.nanoTime()+1_000_000_000L,cancelled::get)).isFalse();
    }
}
