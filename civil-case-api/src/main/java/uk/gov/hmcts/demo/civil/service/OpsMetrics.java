package uk.gov.hmcts.demo.civil.service;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class OpsMetrics {
    private static final long WINDOW=60;
    private final Clock clock;
    private final NavigableMap<Long,long[]> seconds=new TreeMap<>();
    private final Deque<ErrorEntry> errors=new ArrayDeque<>();
    public record ErrorEntry(Instant timestamp,String requestId,String method,String path,String message,int status) {}
    public record Snapshot(String service,long windowSeconds,long requestCount,long errorCount,
            double requestRate,double errorRate,List<ErrorEntry> latestErrors) {}
    public OpsMetrics(Clock clock) { this.clock=clock; }

    public synchronized void record(String requestId,String method,String path,int status,String message) {
        Instant now=clock.instant();
        prune(now.getEpochSecond());
        long[] bucket=seconds.computeIfAbsent(now.getEpochSecond(),s -> new long[2]);
        bucket[0]++;
        if (status>=500) {
            bucket[1]++;
            errors.addFirst(new ErrorEntry(now,requestId,method,path,
                message==null ? "The service could not complete the request" : message,status));
            while(errors.size()>20) errors.removeLast();
        }
    }

    public synchronized Snapshot snapshot() {
        prune(clock.instant().getEpochSecond());
        long requests=0,failures=0;
        for(long[] bucket:seconds.values()) { requests+=bucket[0]; failures+=bucket[1]; }
        return new Snapshot("civil-case-api",WINDOW,requests,failures,requests/(double)WINDOW,
            requests==0?0:failures/(double)requests,List.copyOf(errors));
    }

    private void prune(long now) { seconds.headMap(now-WINDOW+1).clear(); }
}
