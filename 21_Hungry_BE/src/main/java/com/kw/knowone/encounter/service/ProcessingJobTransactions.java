package com.kw.knowone.encounter.service;
import static com.kw.knowone.encounter.entity.EncounterModels.*;
import java.time.Clock;import java.time.Duration;import java.util.List;
import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import com.kw.knowone.common.schedule.ScheduleMutationService;import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisResult;import com.kw.knowone.encounter.processing.AiProcessingPort.TextResult;import com.kw.knowone.encounter.repository.EncounterRepository;
@Service
public class ProcessingJobTransactions {
    private final EncounterRepository repository;private final Clock clock;private final Duration lease;private final ScheduleMutationService schedules;private final AutoTaskApplicationService autoTasks;
    public ProcessingJobTransactions(EncounterRepository repository,Clock clock,ScheduleMutationService schedules,AutoTaskApplicationService autoTasks,@Value("${app.encounter.worker-lease:PT2M}")Duration lease){this.repository=repository;this.clock=clock;this.schedules=schedules;this.autoTasks=autoTasks;this.lease=lease;}
    @Transactional public List<Job> claim(int limit){var now=clock.instant();return repository.claim(limit,now,now.plus(lease));}
    @Transactional public boolean completeText(Job job,String text){boolean applied=repository.completeSourceJob(job,job.leaseToken(),text);if(!applied)return false;if("TRANSCRIBE".equals(job.jobType()))repository.markAudioDeletePending(job.sourceId());Encounter e=repository.findActive(job.encounterId()).orElse(null);if(e!=null&&e.inputVersion()==job.inputVersion()&&repository.allActiveSourcesReady(job.encounterId()))repository.insertJob(job.groupId(),job.encounterId(),null,"ANALYZE",job.inputVersion(),"ready");return true;}
    @Transactional public boolean completeText(Job job,TextResult result){repository.recordProvider(job,result.provider(),result.model(),result.promptVersion());return completeText(job,result.text());}
    public boolean completeAnalysis(Job job,AnalysisResult result){boolean[] applied={false};schedules.executeSystem(()->{applied[0]=repository.completeAnalysis(job,job.leaseToken(),result);if(applied[0])repository.findActive(job.encounterId()).ifPresent(autoTasks::applyReadyLocked);});return applied[0];}
    @Transactional public boolean reschedule(Job job,String code,Duration delay){return repository.reschedule(job,code,clock.instant().plus(delay));}
    @Transactional public void fail(Job job,String code){repository.failJob(job.id(),job.leaseToken(),code);}
    @Transactional public void obsolete(Job job,String code){repository.obsoleteJob(job.id(),job.leaseToken(),code);}
}
