package com.loadsense.core.service;

import com.loadsense.core.client.TargetHttpClient;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Service
public class LoadEngine {
    private final TestConfigService configs;
    private final ExecutionStore store;
    private final TargetPolicy targets;
    private final TargetHttpClient http;
    private final SimpMessagingTemplate realtime;
    private final int maxActive;
    private final ExecutorService coordinators;
    private final Map<UUID,Run> active=new ConcurrentHashMap<>();
    private volatile boolean closing;
    private static final class Run {
        final AtomicBoolean stop=new AtomicBoolean();
        final CountDownLatch wakeup=new CountDownLatch(1);
        final AtomicInteger activeUsers=new AtomicInteger();
        final MetricAccumulator metrics=new MetricAccumulator();
        final ExecutorService workers;
        Run(int users) { workers=Executors.newFixedThreadPool(users); }
    }
    public LoadEngine(TestConfigService configs,ExecutionStore store,TargetPolicy targets,
        TargetHttpClient http,SimpMessagingTemplate realtime,@Value("${loadsense.max-active-executions}") int maxActive) {
        this.configs=configs; this.store=store; this.targets=targets; this.http=http; this.realtime=realtime;
        if(maxActive<1 || maxActive>4) throw new IllegalStateException("MAX_ACTIVE_EXECUTIONS phải từ 1 đến 4");
        this.maxActive=maxActive; this.coordinators=Executors.newFixedThreadPool(maxActive);
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() { store.recoverInterrupted(); }
    public synchronized ExecutionView start(UUID configId,UUID owner,StartRequest input) {
        var c=configs.owned(configId,owner); var snapshot=configs.request(c); targets.validate(snapshot);
        if(!input.authorizedTarget()) throw ApiException.invalid("Cần xác nhận quyền kiểm thử API đích");
        var payloads=configs.payloads(configId,input.mockDataSetId());
        if(!payloads.isEmpty() && Set.of("GET","HEAD").contains(c.httpMethod)) throw ApiException.invalid("GET/HEAD không hỗ trợ mock body");
        if(closing || active.size()>=maxActive) throw ApiException.conflict("Engine đã đạt giới hạn số test đồng thời");
        var e=store.create(c,snapshot); var run=new Run(c.virtualUsers); active.put(e.id,run);
        coordinators.submit(() -> execute(e,snapshot,payloads,run));
        return store.view(e);
    }
    public ExecutionView stop(UUID id,UUID owner) {
        var e=store.owned(id,owner); var run=active.get(id);
        if(run!=null) { run.stop.set(true); run.wakeup.countDown(); run.workers.shutdownNow(); }
        return store.view(e);
    }
    private void execute(TestExecution e,ConfigRequest c,List<String> payloads,Run run) {
        long started=System.nanoTime(), deadline=started+c.durationSeconds()*1_000_000_000L;
        var limiter=new PacedRateLimiter(c.requestsPerSecond());
        var cursor=new AtomicLong(); ExecutionStatus terminal=ExecutionStatus.COMPLETED; String failure=null;
        try {
            store.running(e.id);
            publish(e.userId,new ExecutionEvent(store.get(e.id,e.userId),null));
            for(int i=0;i<c.virtualUsers() && !run.stop.get();i++) run.workers.submit(() -> {
                run.activeUsers.incrementAndGet();
                try {
                    while(limiter.acquire(deadline,run.stop::get)) {
                        if(run.stop.get()) break;
                        String body=payloads.isEmpty() ? c.body() : payloads.get((int)(cursor.getAndIncrement()%payloads.size()));
                        long before=System.nanoTime();
                        try {
                            long remaining=deadline-before; if(remaining<=0) break;
                            int status=http.send(c,body,remaining);
                            run.metrics.record(System.nanoTime()-before,status<200 || status>=400);
                        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                        catch(Exception ex) { run.metrics.record(System.nanoTime()-before,true); }
                    }
                } finally { run.activeUsers.decrementAndGet(); }
            });
            run.workers.shutdown();
            while(!run.stop.get() && System.nanoTime()<deadline) {
                long wait=Math.min(1_000_000_000L,deadline-System.nanoTime()); if(wait<=0) break;
                if(run.wakeup.await(wait,TimeUnit.NANOSECONDS)) break;
                if(System.nanoTime()<deadline)
                    publish(e.userId,store.sample(e.id,run.metrics.sample(e.id,elapsed(started),run.activeUsers.get())));
            }
            if(run.stop.get()) terminal=ExecutionStatus.STOPPED;
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt(); terminal=ExecutionStatus.STOPPED;
        } catch(Exception ex) {
            terminal=run.stop.get() ? ExecutionStatus.STOPPED : ExecutionStatus.FAILED;
            failure=run.stop.get() ? null : "Engine không thể hoàn tất lần chạy; kiểm tra dịch vụ lưu trữ";
        } finally {
            run.workers.shutdownNow();
            try { run.workers.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            try { publish(e.userId,store.finish(e.id,run.metrics.sample(e.id,elapsed(started),0),terminal,failure)); }
            finally { active.remove(e.id); }
        }
    }
    private double elapsed(long start) { return Math.max(0.001,(System.nanoTime()-start)/1_000_000_000.0); }
    private void publish(UUID owner,ExecutionEvent event) {
        realtime.convertAndSendToUser(owner.toString(),"/queue/executions/"+event.execution().id(),event);
    }
    public int activeCount() { return active.size(); }
    @PreDestroy public synchronized void close() {
        closing=true; active.values().forEach(run -> { run.stop.set(true); run.wakeup.countDown(); run.workers.shutdownNow(); });
        coordinators.shutdown();
        try { if(!coordinators.awaitTermination(10,TimeUnit.SECONDS)) coordinators.shutdownNow(); }
        catch(InterruptedException ex) { coordinators.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
