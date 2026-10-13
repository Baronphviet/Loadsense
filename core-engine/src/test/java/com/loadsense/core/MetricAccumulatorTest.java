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
        assertThat(first.p95LatencyMs).isEqualTo(95); assertThat(first.errorRate).isEqualTo(5);
        assertThat(first.throughput).isEqualTo(100);
        accumulator.record(100_000_000L,false);
        assertThat(accumulator.sample(id,2,0).throughput).isEqualTo(1);
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
