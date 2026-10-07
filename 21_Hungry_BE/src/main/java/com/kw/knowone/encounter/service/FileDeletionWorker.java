package com.kw.knowone.encounter.service;
import static com.kw.knowone.encounter.entity.EncounterModels.*;
import java.time.Clock;import java.util.List;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Service;import org.springframework.transaction.support.TransactionTemplate;
import com.kw.knowone.encounter.repository.EncounterRepository;import com.kw.knowone.storage.StoragePort;
@Service
public class FileDeletionWorker {
    private static final Logger log=LoggerFactory.getLogger(FileDeletionWorker.class);private final EncounterRepository repository;private final StoragePort storage;private final Clock clock;private final TransactionTemplate transactions;
    public FileDeletionWorker(EncounterRepository repository,StoragePort storage,Clock clock,TransactionTemplate transactions){this.repository=repository;this.storage=storage;this.clock=clock;this.transactions=transactions;}
    @Scheduled(fixedDelayString="${app.encounter.delete-worker-delay:60000}") public void scheduled(){runOnce(50);}
    public int runOnce(int limit){try{storage.cleanupStagedBefore(clock.instant().minus(java.time.Duration.ofHours(1)));}catch(Exception failure){log.warn("Staged object cleanup failed");}List<Asset> values=repository.assetsPendingDeletion(clock.instant(),limit);for(Asset a:values){try{storage.delete(a.objectKey());markDeleted(a);}catch(Exception failure){markFailed(a);if(a.deleteAttempts()>=3)log.warn("Private object deletion remains pending for asset {}",a.id());}}return values.size();}
    public void markDeleted(Asset a){transactions.executeWithoutResult(status->{repository.deletedAsset(a.id());Source s=repository.sourceByAsset(a.id()).orElse(null);if(s!=null&&repository.find(s.encounterId()).map(v->v.deletedAt()!=null).orElse(false))repository.purgeDeletedSensitive(s.encounterId());});}
    public void markFailed(Asset a){transactions.executeWithoutResult(status->repository.failedAssetDeletion(a.id()));}
}
