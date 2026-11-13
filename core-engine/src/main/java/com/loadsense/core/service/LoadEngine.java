package com.loadsense.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadsense.core.client.AiClient;
import com.loadsense.core.client.TargetHttpClient;
import com.loadsense.core.dto.ApiDtos.*;
import com.loadsense.core.entity.*;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
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
    private final AuthService auth;
    private final AiClient ai;
    private final ObjectMapper json;
    private final int maxActive;
    private final ExecutorService coordinators;
    private final ExecutorService aiExecutor=new ThreadPoolExecutor(
        2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32));
    private final Map<UUID,Run> active=new ConcurrentHashMap<>();
    private volatile boolean closing;
    private static final class Run {
        final AtomicBoolean stop=new AtomicBoolean();
        final AtomicBoolean userStopped=new AtomicBoolean();
        final CountDownLatch wakeup=new CountDownLatch(1);
        final AtomicInteger activeUsers=new AtomicInteger();
        final MetricAccumulator metrics=new MetricAccumulator();
        final ExecutorService workers;
        Run(int users) { workers=Executors.newFixedThreadPool(users); }
    }
    public LoadEngine(TestConfigService configs,ExecutionStore store,TargetPolicy targets,
        TargetHttpClient http,SimpMessagingTemplate realtime,AuthService auth,AiClient ai,ObjectMapper json,
        @Value("${loadsense.max-active-executions}") int maxActive) {
        this.configs=configs; this.store=store; this.targets=targets; this.http=http; this.realtime=realtime;
        this.auth=auth; this.ai=ai; this.json=json;
        if(maxActive<1 || maxActive>4) throw new IllegalStateException("MAX_ACTIVE_EXECUTIONS phải từ 1 đến 4");
        this.maxActive=maxActive; this.coordinators=Executors.newFixedThreadPool(maxActive);
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() { store.recoverInterrupted(); }
    public synchronized ExecutionView start(UUID configId,UUID owner,StartRequest input) {
        var c=configs.owned(configId,owner); var snapshot=configs.request(c); targets.validate(snapshot,owner);
        if(!input.authorizedTarget()) throw ApiException.invalid("Cần xác nhận quyền kiểm thử API đích");
        var payloads=configs.payloads(configId,input.mockDataSetId());
        var steps=snapshot.resolvedSteps();
        String firstMethod=steps.isEmpty() ? c.httpMethod : steps.get(0).httpMethod();
        if(!payloads.isEmpty() && Set.of("GET","HEAD").contains(firstMethod)) throw ApiException.invalid("GET/HEAD không hỗ trợ mock body");
        if(closing || active.size()>=maxActive) throw ApiException.conflict("Engine đã đạt giới hạn số test đồng thời");
        var settings=auth.getSettings(owner);
        boolean shouldAutoAnalyze=input.autoAiAnalyze()!=null ? input.autoAiAnalyze() : settings.autoAiAnalyze();
        var e=store.create(c,snapshot,input.mockDataSetId()); var run=new Run(c.virtualUsers); active.put(e.id,run);
        coordinators.submit(() -> execute(e,snapshot,payloads,run,settings,shouldAutoAnalyze));
        return store.view(e);
    }
    public ExecutionView stop(UUID id,UUID owner) {
        var e=store.owned(id,owner); var run=active.get(id);
        if(run!=null) { run.userStopped.set(true); run.stop.set(true); run.wakeup.countDown(); run.workers.shutdownNow(); }
        return store.view(e);
    }
    private void execute(TestExecution e,ConfigRequest c,List<String> payloads,Run run,UserSettingsDto settings,boolean shouldAutoAnalyze) {
        long durationNanos=c.durationSeconds()*1_000_000_000L;
        long started=System.nanoTime(), deadline=started+durationNanos;
        var limiter=new PacedRateLimiter(c.requestsPerSecond(),c.resolvedProfile(),c.resolvedRampUpSeconds(),durationNanos);
        var cursor=new AtomicLong(); ExecutionStatus terminal=ExecutionStatus.COMPLETED; String failure=null;
        String anomalyWarning=null; String stopReason="COMPLETED"; int consecutiveAnomalies=0; long lastSampleTotal=0;
        var steps=c.resolvedSteps();
        try {
            store.running(e.id);
            publish(e.userId,new ExecutionEvent(store.get(e.id,e.userId),null));
            for(int i=0;i<c.virtualUsers() && !run.stop.get();i++) run.workers.submit(() -> {
                run.activeUsers.incrementAndGet();
                try {
                    while(limiter.acquire(deadline,run.stop::get)) {
                        if(run.stop.get()) break;
                        String payload=payloads.isEmpty() ? null : payloads.get((int)(cursor.getAndIncrement()%payloads.size()));
                        if(steps.isEmpty()) {
                            String body=payload!=null ? payload : c.body();
                            long before=System.nanoTime();
                            try {
                                long remaining=deadline-before; if(remaining<=0) break;
                                int status=http.send(c,body,remaining);
                                run.metrics.record(System.nanoTime()-before,classifyStatus(status));
                            } catch(InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                            catch(Exception ex) { run.metrics.record(System.nanoTime()-before,classifyException(ex)); }
                        } else {
                            if(!executeChain(c,steps,payload,deadline,run)) break;
                        }
                    }
                } finally { run.activeUsers.decrementAndGet(); }
            });
            run.workers.shutdown();
            while(!run.stop.get() && System.nanoTime()<deadline) {
                long wait=Math.min(1_000_000_000L,deadline-System.nanoTime()); if(wait<=0) break;
                if(run.wakeup.await(wait,TimeUnit.NANOSECONDS)) break;
                if(System.nanoTime()<deadline) {
                    var metric=run.metrics.sample(e.id,elapsed(started),run.activeUsers.get());
                    boolean hasIntervalSamples=metric.totalRequests>lastSampleTotal;
                    lastSampleTotal=metric.totalRequests;
                    if(settings.earlyAnomalyEnabled() && metric.totalRequests>=10 && hasIntervalSamples) {
                        boolean errAnomaly=metric.intervalErrorRate>=settings.anomalyErrorRateThreshold();
                        boolean latAnomaly=metric.intervalP95Ms>=settings.anomalyP95ThresholdMs();
                        if(errAnomaly || latAnomaly) {
                            consecutiveAnomalies++;
                            anomalyWarning=String.format(Locale.ROOT,
                                "Early anomaly detected (intervalErrorRate=%.2f%%, intervalP95=%.0fms; thresholds: %.2f%%, %dms)",
                                metric.intervalErrorRate,metric.intervalP95Ms,
                                settings.anomalyErrorRateThreshold(),settings.anomalyP95ThresholdMs());
                            if(settings.autoStopOnAnomaly() && consecutiveAnomalies>=3) {
                                anomalyWarning=anomalyWarning+" — protective auto-stop triggered after "+consecutiveAnomalies+" consecutive anomalous intervals";
                                failure=anomalyWarning;
                                stopReason="ANOMALY";
                                run.stop.set(true);
                                run.wakeup.countDown();
                            }
                        } else {
                            consecutiveAnomalies=0;
                        }
                    }
                    publish(e.userId,store.sample(e.id,metric,run.metrics.errorBreakdown(),anomalyWarning));
                }
            }
            if(run.stop.get()) {
                terminal=ExecutionStatus.STOPPED;
                if(!"ANOMALY".equals(stopReason)) stopReason=run.userStopped.get() ? "USER" : "INTERRUPTED";
            }
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt(); terminal=ExecutionStatus.STOPPED;
            if(!"ANOMALY".equals(stopReason)) stopReason=run.userStopped.get() ? "USER" : "INTERRUPTED";
        } catch(Exception ex) {
            terminal=run.stop.get() ? ExecutionStatus.STOPPED : ExecutionStatus.FAILED;
            failure=run.stop.get() ? null : "Engine không thể hoàn tất lần chạy; kiểm tra dịch vụ lưu trữ";
            stopReason=run.stop.get() ? (run.userStopped.get() ? "USER" : stopReason) : "ERROR";
        } finally {
            run.workers.shutdownNow();
            try { run.workers.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            try {
                publish(e.userId,store.finish(e.id,run.metrics.sample(e.id,elapsed(started),0),run.metrics.errorBreakdown(),anomalyWarning,terminal,failure,stopReason));
            } finally { active.remove(e.id); }
            if(shouldAutoAnalyze && !closing && ai.available()) {
                store.markAiPending(e.id);
                try {
                    aiExecutor.submit(() -> {
                        try {
                            var input=store.aiAnalysisInput(e.id,e.userId);
                            var result=ai.call("/analyze-log",input);
                            store.saveAiAnalysis(e.id,e.userId,json.writeValueAsString(result));
                        } catch(Exception ex) {
                            store.markAiFailed(e.id);
                        }
                        try { publish(e.userId,new ExecutionEvent(store.get(e.id,e.userId),null)); }
                        catch(Exception ignored) {}
                    });
                } catch(RejectedExecutionException ex) {
                    store.markAiFailed(e.id);
                }
            }
        }
    }
    private boolean executeChain(ConfigRequest c,List<ScenarioStep> steps,String firstPayload,long deadline,Run run) {
        Map<String,String> vars=new HashMap<>();
        for(int idx=0;idx<steps.size();idx++) {
            if(run.stop.get()) return false;
            ScenarioStep step=steps.get(idx);
            String stepUrl=substitute(step.targetUrl(),vars,true);
            String rawBody=idx==0 && firstPayload!=null ? firstPayload : step.body();
            String stepBody=substitute(rawBody,vars,false);
            Map<String,String> stepHeaders=substituteHeaders(step.headers(),vars);
            boolean needBody=step.extractVar()!=null && !step.extractVar().isBlank() && step.extractPath()!=null && !step.extractPath().isBlank();
            long before=System.nanoTime();
            try {
                long remaining=deadline-before; if(remaining<=0) return false;
                var result=http.sendWithResult(step.httpMethod(),stepUrl,stepHeaders,stepBody,c.timeoutMs(),remaining,needBody);
                String cat=classifyStatus(result.status());
                run.metrics.record(System.nanoTime()-before,cat);
                if(cat!=null) break;
                if(needBody && result.body()!=null && !result.body().isBlank()) {
                    String extracted=extractJsonValue(result.body(),step.extractPath());
                    if(extracted!=null) vars.put(step.extractVar(),extracted);
                }
            } catch(InterruptedException ex) {
                Thread.currentThread().interrupt(); return false;
            } catch(Exception ex) {
                run.metrics.record(System.nanoTime()-before,classifyException(ex));
                break;
            }
        }
        return true;
    }
    private String extractJsonValue(String rawBody,String path) {
        try {
            JsonNode current=json.readTree(rawBody);
            String clean=path.trim();
            if(clean.startsWith("$.")) clean=clean.substring(2);
            else if(clean.startsWith("$")) clean=clean.substring(1);
            for(String part:clean.split("\\.")) {
                if(part.isEmpty()) continue;
                int bracket=part.indexOf('[');
                if(bracket>=0 && part.endsWith("]")) {
                    String field=part.substring(0,bracket);
                    int arrIdx=Integer.parseInt(part.substring(bracket+1,part.length()-1));
                    if(!field.isEmpty()) current=current.path(field);
                    current=current.path(arrIdx);
                } else {
                    current=current.path(part);
                }
                if(current.isMissingNode() || current.isNull()) return null;
            }
            return current.isValueNode() ? current.asText() : null;
        } catch(Exception ex) {
            return null;
        }
    }
    private static Map<String,String> substituteHeaders(Map<String,String> headers,Map<String,String> vars) {
        if(headers==null || headers.isEmpty() || vars.isEmpty()) return headers;
        Map<String,String> out=new LinkedHashMap<>();
        headers.forEach((k,v) -> out.put(k,substitute(v,vars,true)));
        return out;
    }
    private static String substitute(String text,Map<String,String> vars,boolean stripNewlines) {
        if(text==null || vars.isEmpty() || !text.contains("{{")) return text;
        String out=text;
        for(var entry:vars.entrySet()) {
            String val=entry.getValue();
            if(stripNewlines) val=val.replace("\r","").replace("\n","");
            out=out.replace("{{"+entry.getKey()+"}}",val);
        }
        return out;
    }
    static String classifyStatus(int status) {
        if(status==429) return "http429";
        if(status>=400 && status<500) return "http4xx";
        if(status>=500) return "http5xx";
        if(status<200) return "network";
        return null;
    }
    static String classifyException(Throwable ex) {
        Throwable cur=ex;
        while(cur!=null) {
            if(cur instanceof HttpTimeoutException || cur instanceof SocketTimeoutException
                || cur instanceof TimeoutException || cur instanceof ConnectTimeoutException
                || cur.getClass().getSimpleName().contains("Timeout")) {
                return "timeout";
            }
            cur=cur.getCause();
        }
        return "network";
    }
    private double elapsed(long start) { return Math.max(0.001,(System.nanoTime()-start)/1_000_000_000.0); }
    private void publish(UUID owner,ExecutionEvent event) {
        realtime.convertAndSendToUser(owner.toString(),"/queue/executions/"+event.execution().id(),event);
    }
    public int activeCount() { return active.size(); }
    @PreDestroy public synchronized void close() {
        closing=true; active.values().forEach(run -> { run.stop.set(true); run.wakeup.countDown(); run.workers.shutdownNow(); });
        coordinators.shutdown();
        aiExecutor.shutdownNow();
        try { if(!coordinators.awaitTermination(10,TimeUnit.SECONDS)) coordinators.shutdownNow(); }
        catch(InterruptedException ex) { coordinators.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
