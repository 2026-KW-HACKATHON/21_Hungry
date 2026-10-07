package com.kw.knowone.encounter.service;
import static com.kw.knowone.encounter.entity.EncounterModels.*;
import java.time.Clock;import java.time.Duration;import java.time.Instant;import java.util.List;import java.util.concurrent.ExecutorService;import java.util.concurrent.Executors;import java.util.concurrent.Future;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Service;
import com.kw.knowone.encounter.processing.AiProcessingPort;import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisInput;import com.kw.knowone.encounter.processing.AiProcessingPort.SourceText;import com.kw.knowone.encounter.processing.AiProviderException;import com.kw.knowone.encounter.repository.EncounterRepository;import com.kw.knowone.storage.StoragePort;
@Service
public class EncounterProcessingWorker {
    private static final Logger log=LoggerFactory.getLogger(EncounterProcessingWorker.class);
    private final ProcessingJobTransactions tx;private final EncounterRepository repository;private final StoragePort storage;private final AiProcessingPort ai;private final boolean enabled;private final int concurrency,maxAttempts,maxInputCodePoints;private final Clock clock;private final ExecutorService executor;
    public EncounterProcessingWorker(ProcessingJobTransactions tx,EncounterRepository repository,StoragePort storage,AiProcessingPort ai,Clock clock,
            @Value("${app.encounter.worker-enabled:false}")boolean enabled,@Value("${app.ai.worker-concurrency:2}")int concurrency,
            @Value("${app.ai.job-max-attempts:4}")int maxAttempts,@Value("${app.ai.max-input-code-points:300000}")int maxInputCodePoints,
            @Value("${app.encounter.worker-lease:PT3M}")Duration lease,@Value("${app.ai.openai.timeout:PT90S}")Duration callTimeout){this.tx=tx;this.repository=repository;this.storage=storage;this.ai=ai;this.clock=clock;this.enabled=enabled;this.concurrency=Math.max(1,concurrency);this.maxAttempts=maxAttempts;this.maxInputCodePoints=maxInputCodePoints;this.executor=enabled&&this.concurrency>1?Executors.newFixedThreadPool(this.concurrency,Thread.ofPlatform().name("ai-worker-",0).factory()):null;
        if(enabled&&!lease.minus(callTimeout).minusSeconds(15).isPositive())throw new IllegalStateException("AI worker lease must exceed the provider timeout by at least 15 seconds");}
    @Scheduled(fixedDelayString="${app.encounter.worker-delay:2000}") public void scheduled(){if(enabled)runOnce(concurrency);}
    public int runOnce(int limit){if(!enabled)return 0;List<Job> jobs=tx.claim(Math.min(limit,concurrency));if(executor==null)jobs.forEach(this::process);else{var futures=jobs.stream().map(job->executor.submit(()->process(job))).toList();for(Future<?> future:futures)try{future.get();}catch(Exception failure){log.warn("AI worker task join failed",failure);}}return jobs.size();}
    @PreDestroy void shutdown(){if(executor!=null)executor.shutdown();}
    private void process(Job job){Instant started=clock.instant();try{Encounter e=repository.findActive(job.encounterId()).orElse(null);if(e==null||e.inputVersion()!=job.inputVersion()){tx.obsolete(job,"OBSOLETE_INPUT");return;}
        if("ANALYZE".equals(job.jobType())){if(repository.anyActiveSourceFailed(job.encounterId())){tx.fail(job,"SOURCE_PROCESSING_FAILED");return;}
            List<SourceText> texts=repository.sources(job.encounterId()).stream().filter(v->"READY".equals(v.status())).map(v->new SourceText(v.id().toString(),v.textVersion(),v.sourceType(),v.asset().mediaType(),v.extractedText())).toList();
            int size=texts.stream().mapToInt(v->v.text()==null?0:v.text().codePointCount(0,v.text().length())).sum();if(size>maxInputCodePoints){tx.fail(job,"AI_INPUT_LIMIT_EXCEEDED");return;}
            var result=ai.analyze(new AnalysisInput(e.occurredOn(),clock.instant(),"Asia/Seoul",texts,repository.knownMedications(e.groupId())));if(tx.completeAnalysis(job,result))logSuccess(job,result.provider(),result.model(),result.inputTokens(),result.outputTokens(),started);return;}
        Source s=repository.activeSource(job.sourceId()).orElse(null);if(s==null){tx.obsolete(job,"SOURCE_REMOVED");return;}if(!"AVAILABLE".equals(s.asset().state())){tx.fail(job,"SOURCE_UNAVAILABLE");return;}
        byte[] bytes;try(StoragePort.StoredObject value=storage.read(s.asset().objectKey())){bytes=value.input().readAllBytes();}
        var result="TRANSCRIBE".equals(job.jobType())?ai.transcribe(bytes,s.asset().mediaType()):ai.ocr(bytes,s.asset().mediaType());
        if(result.text()==null||result.text().isBlank()){tx.fail(job,"AI_OUTPUT_INVALID");return;}if(tx.completeText(job,result))logSuccess(job,result.provider(),result.model(),result.inputTokens(),result.outputTokens(),started);
    }catch(AiProviderException failure){if(failure.retryable()&&job.attemptCount()<maxAttempts){Duration backoff=Duration.ofSeconds(Math.min(60,1L<<Math.min(job.attemptCount(),5)));Duration delay=failure.retryAfter()==null?backoff:failure.retryAfter().compareTo(Duration.ofMinutes(5))>0?Duration.ofMinutes(5):failure.retryAfter();tx.reschedule(job,failure.code(),delay);}else tx.fail(job,failure.code());}
    catch(Exception failure){log.warn("AI job {} type {} failed before safe error mapping",job.id(),job.jobType());if(job.attemptCount()<maxAttempts)tx.reschedule(job,"AI_PROVIDER_UNAVAILABLE",Duration.ofSeconds(5));else tx.fail(job,"AI_PROVIDER_UNAVAILABLE");}}
    private void logSuccess(Job job,String provider,String model,long inputTokens,long outputTokens,Instant started){log.info("AI job {} type {} completed provider={} model={} inputTokens={} outputTokens={} elapsedMs={}",job.id(),job.jobType(),provider,model,inputTokens,outputTokens,Duration.between(started,clock.instant()).toMillis());}
}
