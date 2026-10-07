package com.kw.knowone.encounter.repository;

import static com.kw.knowone.encounter.entity.EncounterModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisResult;
import com.kw.knowone.encounter.processing.AiProcessingPort.KnownMedication;

@Repository
public class EncounterRepository {
    private final JdbcTemplate jdbc;
    public EncounterRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public Optional<Encounter> find(UUID id){return encounters("WHERE e.id=?",id).stream().findFirst();}
    public Optional<Encounter> findActive(UUID id){return encounters("WHERE e.id=? AND e.deleted_at IS NULL",id).stream().findFirst();}
    public Encounter lockActive(UUID id){return jdbc.query("""
            SELECT e.id,e.group_id,e.created_by,u.display_name,e.record_type,e.occurred_on,e.hospital_name,e.title,
                   e.input_version,e.deleted_at,e.created_at,e.version
            FROM encounter e JOIN app_user u ON u.id=e.created_by
            WHERE e.id=? AND e.deleted_at IS NULL FOR UPDATE OF e
            """,this::mapEncounter,id).stream().findFirst().orElse(null);}
    public List<Encounter> list(UUID groupId,LocalDate from,LocalDate to,String type,LocalDate cursorDate,Instant cursorTime,UUID cursorId,int fetch){
        StringBuilder where=new StringBuilder("WHERE e.group_id=? AND e.deleted_at IS NULL");java.util.ArrayList<Object> args=new java.util.ArrayList<>();args.add(groupId);
        if(from!=null){where.append(" AND e.occurred_on>=?");args.add(from);}if(to!=null){where.append(" AND e.occurred_on<?");args.add(to);}
        if(type!=null){where.append(" AND e.record_type=?");args.add(type);}
        if(cursorTime!=null){if(cursorDate==null){where.append(" AND e.occurred_on IS NULL AND (e.created_at,e.id)<(?,?)");args.add(Timestamp.from(cursorTime));args.add(cursorId);}else{where.append(" AND (e.occurred_on<? OR e.occurred_on IS NULL OR (e.occurred_on=? AND (e.created_at,e.id)<(?,?)))");args.add(cursorDate);args.add(cursorDate);args.add(Timestamp.from(cursorTime));args.add(cursorId);}}
        where.append(" ORDER BY e.occurred_on DESC NULLS LAST,e.created_at DESC,e.id DESC LIMIT ?");args.add(fetch);
        return encounters(where.toString(),args.toArray());
    }
    public Encounter insert(UUID id,UUID groupId,UUID userId,String type,String title,LocalDate occurredOn,String hospital){
        jdbc.update("INSERT INTO encounter(id,group_id,created_by,record_type,title,occurred_on,hospital_name) VALUES (?,?,?,?,?,?,?)",
                id,groupId,userId,type,title,occurredOn,hospital);return findActive(id).orElseThrow();}
    public boolean updateMeta(UUID id,long version,String title,LocalDate occurredOn,String hospital){
        return jdbc.update("UPDATE encounter SET title=?,occurred_on=?,hospital_name=?,version=version+1 WHERE id=? AND version=? AND deleted_at IS NULL",
                title,occurredOn,hospital,id,version)==1;}
    public boolean bumpInput(UUID id,long version,int inputVersion){return jdbc.update("UPDATE encounter SET input_version=input_version+1,version=version+1 WHERE id=? AND version=? AND input_version=? AND deleted_at IS NULL",id,version,inputVersion)==1;}

    public UUID insertAsset(UUID groupId,UUID userId,String purpose,String key,String name,String media,long size,byte[] sha,Instant expires){
        UUID id=UUID.randomUUID();jdbc.update("""
                INSERT INTO file_asset(id,group_id,uploaded_by,purpose,object_key,original_name,media_type,byte_size,sha256,state,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,'AVAILABLE',?)
                """,id,groupId,userId,purpose,key,name,media,size,sha,expires==null?null:Timestamp.from(expires));return id;}
    public UUID insertSource(UUID groupId,UUID encounterId,UUID assetId,String type){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO encounter_source(id,group_id,encounter_id,asset_id,source_type) VALUES (?,?,?,?,?)",id,groupId,encounterId,assetId,type);return id;}
    public UUID insertJob(UUID groupId,UUID encounterId,UUID sourceId,String type,int inputVersion,String suffix){UUID id=UUID.randomUUID();String key="ANALYZE".equals(type)?encounterId+":"+inputVersion+":"+type:encounterId+":"+inputVersion+":"+type+":"+sourceId;jdbc.update("""
            INSERT INTO processing_job(id,group_id,encounter_id,source_id,job_type,input_version,dedup_key)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT (dedup_key) DO NOTHING
            """,id,groupId,encounterId,sourceId,type,inputVersion,key);return jdbc.query("SELECT id FROM processing_job WHERE dedup_key=?",(rs,n)->rs.getObject(1,UUID.class),key).getFirst();}
    public void markJobNotConfigured(UUID id){jdbc.update("UPDATE processing_job SET status='FAILED',error_code='AI_NOT_CONFIGURED' WHERE id=? AND status='QUEUED'",id);jdbc.update("UPDATE encounter_source s SET status='FAILED' FROM processing_job j WHERE j.id=? AND j.source_id=s.id AND s.removed_at IS NULL",id);}
    public int activeImageCount(UUID encounterId){return jdbc.queryForObject("""
            SELECT count(*) FROM encounter_source s JOIN file_asset f ON f.id=s.asset_id
            WHERE s.encounter_id=? AND s.removed_at IS NULL AND s.source_type='DOCUMENT'
              AND f.media_type IN ('image/jpeg','image/png','image/webp')
            """,Integer.class,encounterId);}
    public List<Source> sources(UUID encounterId){return jdbc.query(sourceSelect()+" WHERE s.encounter_id=? AND s.removed_at IS NULL ORDER BY s.created_at,s.id",this::mapSource,encounterId);}
    public Optional<Source> source(UUID id){return jdbc.query(sourceSelect()+" WHERE s.id=?",this::mapSource,id).stream().findFirst();}
    public Optional<Source> sourceByAsset(UUID id){return jdbc.query(sourceSelect()+" WHERE s.asset_id=?",this::mapSource,id).stream().findFirst();}
    public Optional<Source> activeSource(UUID id){return jdbc.query(sourceSelect()+" WHERE s.id=? AND s.removed_at IS NULL",this::mapSource,id).stream().findFirst();}
    public List<Job> jobs(UUID encounterId,int inputVersion){return jdbc.query("SELECT * FROM processing_job WHERE encounter_id=? AND input_version=? ORDER BY created_at,id",this::mapJob,encounterId,inputVersion);}
    public Optional<Job> job(UUID id){return jdbc.query("SELECT * FROM processing_job WHERE id=?",this::mapJob,id).stream().findFirst();}
    public Optional<Revision> currentRevision(UUID encounterId){return jdbc.query("SELECT * FROM encounter_revision WHERE encounter_id=? AND is_current",this::mapRevision,encounterId).stream().findFirst();}
    public List<ReviewItem> currentReviewItems(UUID encounterId){return jdbc.query("""
            SELECT i.*,reviewer.display_name reviewer_name FROM extracted_item i JOIN encounter_revision r ON r.id=i.revision_id
            LEFT JOIN app_user reviewer ON reviewer.id=i.reviewed_by
            WHERE i.encounter_id=? AND r.is_current ORDER BY i.created_at,i.id
            """,(r,n)->new ReviewItem(r.getObject("id",UUID.class),r.getObject("encounter_id",UUID.class),r.getObject("revision_id",UUID.class),r.getString("item_type"),r.getString("payload"),r.getString("evidence"),r.getString("review_state"),(String[])r.getArray("review_reasons").getArray(),r.getObject("reviewed_by",UUID.class),r.getString("reviewer_name"),instant(r,"reviewed_at"),r.getLong("version")),encounterId);}
    public ReviewItem lockCurrentReviewItem(UUID encounterId,UUID itemId){return jdbc.query("""
            SELECT i.*,reviewer.display_name reviewer_name FROM extracted_item i JOIN encounter_revision r ON r.id=i.revision_id
            LEFT JOIN app_user reviewer ON reviewer.id=i.reviewed_by
            WHERE i.encounter_id=? AND i.id=? AND r.is_current FOR UPDATE OF i
            """,this::mapReviewItem,encounterId,itemId).stream().findFirst().orElse(null);}
    public boolean dismissReviewItem(UUID id,long version,UUID userId,Instant now){return jdbc.update("""
            UPDATE extracted_item SET review_state='DISMISSED',reviewed_by=?,reviewed_at=?,version=version+1,updated_at=?
            WHERE id=? AND version=? AND review_state IN ('NEEDS_REVIEW','READY')
            """,userId,Timestamp.from(now),Timestamp.from(now),id,version)==1;}
    public boolean applyReviewItem(UUID id,long version,UUID userId,Instant now,String payload,String applicationKey){return jdbc.update("""
            UPDATE extracted_item SET review_state='APPLIED',payload=CAST(? AS jsonb),reviewed_by=?,reviewed_at=?,
              application_key=?,applied_at=?,version=version+1,updated_at=?
            WHERE id=? AND version=? AND review_state IN ('NEEDS_REVIEW','READY')
            """,payload,userId,Timestamp.from(now),applicationKey,Timestamp.from(now),Timestamp.from(now),id,version)==1;}
    public boolean applyReviewItemAutomatically(UUID id,long version,Instant now,String applicationKey){return jdbc.update("""
            UPDATE extracted_item SET review_state='APPLIED',application_key=?,applied_at=?,version=version+1,updated_at=?
            WHERE id=? AND version=? AND item_type='TASK' AND review_state='READY'
            """,applicationKey,Timestamp.from(now),Timestamp.from(now),id,version)==1;}
    public UUID insertMedication(UUID groupId,UUID itemId,UUID supersedesId,String name,String dose,String frequency,
            LocalDate starts,LocalDate ends,String instructions,UUID userId){UUID id=UUID.randomUUID();jdbc.update("""
            INSERT INTO medication_order(id,group_id,source_item_id,supersedes_id,name,dose_text,frequency_text,
              starts_on,ends_on,instructions,confirmed_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,groupId,itemId,supersedesId,name,dose,frequency,starts,ends,instructions,userId);return id;}
    public boolean medicationBelongsToGroup(UUID id,UUID groupId){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM medication_order WHERE id=? AND group_id=?)",Boolean.class,id,groupId));}
    public List<com.kw.knowone.task.dto.TaskDtos.Medication> medications(UUID groupId,UUID encounterId,LocalDate onDate,
            LocalDate cursorDate,UUID cursorId,int fetch){StringBuilder sql=new StringBuilder("""
            SELECT m.id,m.name,m.dose_text,m.frequency_text,m.starts_on,m.ends_on,m.instructions,
              m.confirmed_by,u.display_name,m.confirmed_at,m.supersedes_id
            FROM medication_order m JOIN app_user u ON u.id=m.confirmed_by JOIN extracted_item x ON x.id=m.source_item_id
            WHERE m.group_id=?
            """);java.util.ArrayList<Object> args=new java.util.ArrayList<>();args.add(groupId);
        if(encounterId!=null){sql.append(" AND x.encounter_id=?");args.add(encounterId);}if(onDate!=null){sql.append(" AND m.starts_on<=? AND m.ends_on>=? AND NOT EXISTS(SELECT 1 FROM medication_order newer WHERE newer.supersedes_id=m.id AND newer.starts_on<=?)");args.add(onDate);args.add(onDate);args.add(onDate);}
        if(cursorDate!=null){sql.append(" AND (m.starts_on,m.id)<(?,?)");args.add(cursorDate);args.add(cursorId);}sql.append(" ORDER BY m.starts_on DESC,m.id DESC LIMIT ?");args.add(fetch);
        return jdbc.query(sql.toString(),(r,n)->new com.kw.knowone.task.dto.TaskDtos.Medication(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getObject(5,LocalDate.class),r.getObject(6,LocalDate.class),r.getString(7),new com.kw.knowone.task.dto.TaskDtos.UserRef(r.getObject(8,UUID.class),r.getString(9)),r.getTimestamp(10).toInstant().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime(),r.getObject(11,UUID.class)),args.toArray());}
    public boolean hasReviewItems(UUID encounterId){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM extracted_item WHERE encounter_id=? AND review_state='NEEDS_REVIEW')",Boolean.class,encounterId));}
    public boolean allActiveSourcesReady(UUID encounterId){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM encounter_source WHERE encounter_id=? AND removed_at IS NULL) AND NOT EXISTS(SELECT 1 FROM encounter_source WHERE encounter_id=? AND removed_at IS NULL AND status<>'READY')",Boolean.class,encounterId,encounterId));}
    public boolean anyActiveSourceFailed(UUID encounterId){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM encounter_source WHERE encounter_id=? AND removed_at IS NULL AND status='FAILED')",Boolean.class,encounterId));}
    public boolean hasActiveSource(UUID encounterId){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM encounter_source WHERE encounter_id=? AND removed_at IS NULL)",Boolean.class,encounterId));}
    public List<KnownMedication> knownMedications(UUID groupId){return jdbc.query("""
            SELECT name,dose_text,frequency_text,starts_on,ends_on FROM medication_order
            WHERE group_id=? ORDER BY ends_on DESC,id DESC LIMIT 100
            """,(r,n)->new KnownMedication(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,LocalDate.class),r.getObject(5,LocalDate.class)),groupId);}

    public boolean updateAudioText(UUID sourceId,int textVersion,String text){return jdbc.update("UPDATE encounter_source SET extracted_text=?,text_version=text_version+1,status='READY' WHERE id=? AND text_version=? AND removed_at IS NULL AND source_type='AUDIO' AND status='READY'",text,sourceId,textVersion)==1;}
    public void obsoleteJobs(UUID encounterId,int currentInput){jdbc.update("UPDATE processing_job SET status='OBSOLETE',lease_token=NULL,lease_until=NULL WHERE encounter_id=? AND input_version<? AND status IN ('QUEUED','RUNNING','FAILED')",encounterId,currentInput);}
    public boolean removeSource(UUID sourceId,Instant now){return jdbc.update("UPDATE encounter_source SET removed_at=? WHERE id=? AND removed_at IS NULL",Timestamp.from(now),sourceId)==1;}
    public void markDeletePending(UUID assetId){jdbc.update("UPDATE file_asset SET state='DELETE_PENDING' WHERE id=? AND state IN ('AVAILABLE','FAILED')",assetId);}
    public boolean retry(UUID jobId,int inputVersion){int changed=jdbc.update("""
            UPDATE processing_job SET status='QUEUED',attempt_count=attempt_count+1,error_code=NULL,available_at=now()
            WHERE id=? AND input_version=? AND status='FAILED' AND error_code IN ('AI_RATE_LIMITED','AI_TIMEOUT','AI_PROVIDER_UNAVAILABLE')
              AND EXISTS(SELECT 1 FROM encounter e WHERE e.id=processing_job.encounter_id AND e.deleted_at IS NULL AND e.input_version=processing_job.input_version)
              AND (job_type='ANALYZE' OR EXISTS(SELECT 1 FROM encounter_source s JOIN file_asset f ON f.id=s.asset_id WHERE s.id=processing_job.source_id AND s.removed_at IS NULL AND f.state='AVAILABLE'))
            """,jobId,inputVersion);if(changed==1)jdbc.update("UPDATE encounter_source s SET status='PENDING' FROM processing_job j WHERE j.id=? AND j.source_id=s.id AND s.removed_at IS NULL",jobId);return changed==1;}
    public boolean canRetry(Job job){if(!"FAILED".equals(job.status())||!List.of("AI_RATE_LIMITED","AI_TIMEOUT","AI_PROVIDER_UNAVAILABLE").contains(job.errorCode()))return false;return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM encounter e WHERE e.id=? AND e.deleted_at IS NULL AND e.input_version=?)
              AND (?='ANALYZE' OR EXISTS(SELECT 1 FROM encounter_source s JOIN file_asset f ON f.id=s.asset_id WHERE s.id=? AND s.removed_at IS NULL AND f.state='AVAILABLE'))
            """,Boolean.class,job.encounterId(),job.inputVersion(),job.jobType(),job.sourceId()));}

    public List<Job> claim(int limit,Instant now,Instant leaseUntil){return jdbc.query("""
            WITH picked AS (
              SELECT id FROM processing_job WHERE (status='QUEUED' AND available_at<=?) OR (status='RUNNING' AND lease_until<=?)
              ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT ?
            )
            UPDATE processing_job j SET status='RUNNING',lease_token=gen_random_uuid(),lease_until=?,attempt_count=attempt_count+1
            FROM picked WHERE j.id=picked.id RETURNING j.*
            """,this::mapJob,Timestamp.from(now),Timestamp.from(now),limit,Timestamp.from(leaseUntil));}
    public boolean completeSourceJob(Job job,UUID token,String text){int changed=jdbc.update("""
            UPDATE processing_job SET status='SUCCEEDED',lease_token=NULL,lease_until=NULL,error_code=NULL
            WHERE id=? AND status='RUNNING' AND lease_token=? AND input_version=?
            """,job.id(),token,job.inputVersion());if(changed!=1)return false;
        changed=jdbc.update("""
            UPDATE encounter_source s SET extracted_text=?,text_version=text_version+1,status='READY'
            FROM encounter e WHERE s.id=? AND e.id=s.encounter_id AND e.deleted_at IS NULL AND e.input_version=? AND s.removed_at IS NULL
            """,text,job.sourceId(),job.inputVersion());if(changed!=1){jdbc.update("UPDATE processing_job SET status='OBSOLETE' WHERE id=?",job.id());return false;}return true;}
    public boolean completeAnalysis(Job job,UUID token,String summary,String details,String evidence){
        return completeAnalysis(job,token,new AnalysisResult(summary,details,evidence));
    }
    public boolean completeAnalysis(Job job,UUID token,AnalysisResult result){
        Integer valid=jdbc.queryForObject("SELECT count(*) FROM encounter WHERE id=? AND deleted_at IS NULL AND input_version=?",Integer.class,job.encounterId(),job.inputVersion());
        if(valid==0||jdbc.update("""
                UPDATE processing_job SET status='SUCCEEDED',lease_token=NULL,lease_until=NULL,error_code=NULL,
                  provider=?,model=?,prompt_version=? WHERE id=? AND status='RUNNING' AND lease_token=?
                """,result.provider(),result.model(),result.promptVersion(),job.id(),token)!=1)return false;
        jdbc.update("UPDATE encounter_revision SET is_current=false WHERE encounter_id=? AND is_current",job.encounterId());
        UUID revisionId=jdbc.query("SELECT id FROM encounter_revision WHERE encounter_id=? AND input_version=?",
                (rs,n)->rs.getObject(1,UUID.class),job.encounterId(),job.inputVersion()).stream().findFirst().orElse(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO encounter_revision(id,group_id,encounter_id,input_version,job_id,summary,details,evidence,is_current)
                VALUES (?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),true)
                ON CONFLICT (encounter_id,input_version) DO UPDATE SET job_id=EXCLUDED.job_id,summary=EXCLUDED.summary,
                  details=EXCLUDED.details,evidence=EXCLUDED.evidence,is_current=true
                """,revisionId,job.groupId(),job.encounterId(),job.inputVersion(),job.id(),result.summary()==null?"":result.summary(),result.detailsJson(),result.evidenceJson());
        jdbc.update("UPDATE extracted_item SET review_state='SUPERSEDED',version=version+1 WHERE encounter_id=? AND revision_id<>? AND review_state IN ('NEEDS_REVIEW','READY')",job.encounterId(),revisionId);
        for(var item:result.items()){
            boolean applied=Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM extracted_item WHERE encounter_id=? AND item_type=?
                      AND review_state='APPLIED' AND payload=CAST(? AS jsonb))
                    """,Boolean.class,job.encounterId(),item.itemType(),item.payloadJson()));
            String state=applied?"SUPERSEDED":item.reviewState();List<String> reasons=new java.util.ArrayList<>(item.reviewReasons());
            if(applied)reasons.add("ALREADY_APPLIED");String array="{"+reasons.stream().distinct().map(v->v.replaceAll("[^A-Za-z0-9_]","_")).collect(java.util.stream.Collectors.joining(","))+"}";
            jdbc.update("""
                    INSERT INTO extracted_item(id,group_id,encounter_id,revision_id,item_key,item_type,payload,evidence,review_state,review_reasons)
                    VALUES (?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?::text[])
                    ON CONFLICT (revision_id,item_key) DO UPDATE SET payload=EXCLUDED.payload,evidence=EXCLUDED.evidence,
                      review_state=EXCLUDED.review_state,review_reasons=EXCLUDED.review_reasons,version=extracted_item.version+1
                    """,UUID.randomUUID(),job.groupId(),job.encounterId(),revisionId,item.itemKey(),item.itemType(),item.payloadJson(),item.evidenceJson(),state,array);
        }
        boolean review=result.items().stream().anyMatch(item->"NEEDS_REVIEW".equals(item.reviewState()));
        jdbc.update("""
            INSERT INTO notification_event(id,group_id,event_type,event_key,encounter_id,due_at,payload)
            VALUES (gen_random_uuid(),?,?,?, ?,now(),jsonb_build_object('schemaVersion',1,'revisionId',?::text))
            ON CONFLICT (event_key) DO NOTHING
            """,job.groupId(),review?"RECORD_REVIEW_REQUIRED":"RECORD_READY","record:"+revisionId+":ready",job.encounterId(),revisionId);
        return true;}
    public void recordProvider(Job job,String provider,String model,String promptVersion){jdbc.update("""
            UPDATE processing_job SET provider=?,model=?,prompt_version=? WHERE id=? AND status='RUNNING' AND lease_token=?
            """,provider,model,promptVersion,job.id(),job.leaseToken());}
    public boolean reschedule(Job job,String code,Instant availableAt){return jdbc.update("""
            UPDATE processing_job SET status='QUEUED',lease_token=NULL,lease_until=NULL,error_code=?,available_at=?
            WHERE id=? AND status='RUNNING' AND lease_token=?
            """,code,Timestamp.from(availableAt),job.id(),job.leaseToken())==1;}
    public void failJob(UUID id,UUID token,String code){int changed=jdbc.update("UPDATE processing_job SET status='FAILED',lease_token=NULL,lease_until=NULL,error_code=? WHERE id=? AND status='RUNNING' AND lease_token=?",code,id,token);if(changed==1)jdbc.update("UPDATE encounter_source s SET status='FAILED' FROM processing_job j WHERE j.id=? AND j.source_id=s.id AND s.removed_at IS NULL",id);}
    public void obsoleteJob(UUID id,UUID token,String code){jdbc.update("UPDATE processing_job SET status='OBSOLETE',lease_token=NULL,lease_until=NULL,error_code=? WHERE id=? AND status='RUNNING' AND lease_token=?",code,id,token);}
    public void markAudioDeletePending(UUID sourceId){jdbc.update("UPDATE file_asset f SET state='DELETE_PENDING' FROM encounter_source s WHERE s.id=? AND f.id=s.asset_id AND f.state='AVAILABLE'",sourceId);}

    public List<Asset> assetsPendingDeletion(Instant expiredBefore,int limit){return jdbc.query("""
            SELECT * FROM file_asset WHERE (state='DELETE_PENDING' OR (purpose='AUDIO' AND state<>'DELETED' AND expires_at<=?))
            ORDER BY expires_at NULLS LAST,created_at LIMIT ?
            """,this::mapAsset,Timestamp.from(expiredBefore),limit);}
    public void deletedAsset(UUID id){jdbc.update("UPDATE file_asset SET state='DELETED',object_key=NULL,deleted_at=now() WHERE id=? AND state<>'DELETED'",id);}
    public void failedAssetDeletion(UUID id){jdbc.update("UPDATE file_asset SET delete_attempts=delete_attempts+1,state='DELETE_PENDING' WHERE id=? AND state<>'DELETED'",id);}

    public DeletionImpact deletionImpact(UUID encounterId){
        List<UUID> sources=jdbc.query("SELECT id FROM encounter_source WHERE encounter_id=? AND removed_at IS NULL ORDER BY id",(rs,n)->rs.getObject(1,UUID.class),encounterId);
        List<UUID> medications=jdbc.query("SELECT m.id FROM medication_order m JOIN extracted_item i ON i.id=m.source_item_id WHERE i.encounter_id=? ORDER BY m.id",(rs,n)->rs.getObject(1,UUID.class),encounterId);
        List<UUID> cancel=new java.util.ArrayList<>();List<UUID> retain=new java.util.ArrayList<>();
        if(!medications.isEmpty()){String marks=String.join(",",java.util.Collections.nCopies(medications.size(),"?"));java.util.ArrayList<Object> twice=new java.util.ArrayList<>(medications);twice.addAll(medications);
        cancel.addAll(jdbc.query("""
            SELECT DISTINCT o.id FROM task_occurrence o JOIN occurrence_medication om ON om.occurrence_id=o.id
            WHERE o.status='PENDING' AND o.starts_at>=now() AND om.medication_id IN (%s) AND NOT EXISTS(
              SELECT 1 FROM occurrence_medication other WHERE other.occurrence_id=o.id AND other.medication_id NOT IN (%s)) ORDER BY o.id
            """.formatted(marks,marks),(rs,n)->rs.getObject(1,UUID.class),twice.toArray()));
        retain.addAll(jdbc.query("""
            SELECT DISTINCT o.id FROM task_occurrence o JOIN occurrence_medication om ON om.occurrence_id=o.id
            WHERE om.medication_id IN (%s) AND EXISTS(SELECT 1 FROM occurrence_medication other WHERE other.occurrence_id=o.id AND other.medication_id NOT IN (%s)) ORDER BY o.id
            """.formatted(marks,marks),(rs,n)->rs.getObject(1,UUID.class),twice.toArray()));}
        cancel.addAll(jdbc.query("""
                SELECT o.id FROM task_occurrence o JOIN task_series s ON s.id=o.series_id JOIN extracted_item i ON i.id=s.source_item_id
                WHERE i.encounter_id=? AND o.status='PENDING' AND o.starts_at>=now() ORDER BY o.id
                """,(rs,n)->rs.getObject(1,UUID.class),encounterId));
        cancel=new java.util.ArrayList<>(new java.util.LinkedHashSet<>(cancel));cancel.removeAll(retain);
        List<String> fp=new java.util.ArrayList<>();sources.forEach(id->fp.add("s:"+id));medications.forEach(id->fp.add("m:"+id));cancel.forEach(id->fp.add("c:"+id));retain.forEach(id->fp.add("r:"+id));
        return new DeletionImpact(sources,cancel,retain,medications,fp);
    }
    public void acceptDeletion(UUID encounterId,Instant now){jdbc.update("UPDATE encounter SET deleted_at=?,version=version+1 WHERE id=? AND deleted_at IS NULL",Timestamp.from(now),encounterId);jdbc.update("UPDATE processing_job SET status='OBSOLETE',lease_token=NULL,lease_until=NULL WHERE encounter_id=? AND status IN ('QUEUED','RUNNING','FAILED')",encounterId);jdbc.update("UPDATE encounter_source SET removed_at=COALESCE(removed_at,?) WHERE encounter_id=?",Timestamp.from(now),encounterId);jdbc.update("UPDATE file_asset f SET state='DELETE_PENDING' FROM encounter_source s WHERE s.encounter_id=? AND f.id=s.asset_id AND f.state<>'DELETED'",encounterId);}
    public void applyDeletionImpact(UUID encounterId,List<UUID> medications,List<UUID> cancel,List<UUID> retain){
        if(!cancel.isEmpty()){String taskMarks=String.join(",",java.util.Collections.nCopies(cancel.size(),"?"));jdbc.update("""
                UPDATE task_series s SET stop_from_date=LEAST(COALESCE(s.stop_from_date,x.anchor),x.anchor),version=version+1
                FROM (SELECT series_id,min(anchor_date) anchor FROM task_occurrence WHERE id IN (%s) GROUP BY series_id) x WHERE s.id=x.series_id
                """.formatted(taskMarks),cancel.toArray());}
        if(!medications.isEmpty()){String marks=String.join(",",java.util.Collections.nCopies(medications.size(),"?"));jdbc.update("DELETE FROM occurrence_medication WHERE medication_id IN ("+marks+")",medications.toArray());jdbc.update("DELETE FROM series_medication WHERE medication_id IN ("+marks+")",medications.toArray());}
        for(UUID id:cancel){jdbc.update("UPDATE task_occurrence SET status='CANCELED',cancel_reason='RECORD_DELETED',canceled_at=now(),assignee_user_id=NULL,assignment_origin=NULL,version=version+1 WHERE id=? AND status='PENDING'",id);jdbc.update("UPDATE handoff_request SET status='CLOSED',closed_at=now(),close_reason='RECORD_DELETED',version=version+1 WHERE occurrence_id=? AND status='OPEN'",id);jdbc.update("UPDATE notification_event SET status='CANCELED',lease_token=NULL,lease_until=NULL WHERE occurrence_id=? AND status IN ('PENDING','RUNNING','FAILED')",id);jdbc.update("UPDATE notification_delivery d SET status='CANCELED',lease_token=NULL,lease_until=NULL FROM notification n JOIN notification_event e ON e.id=n.event_id WHERE d.notification_id=n.id AND e.occurrence_id=? AND d.status IN ('PENDING','RUNNING','FAILED')",id);}
        jdbc.update("UPDATE notification_event SET status='CANCELED',lease_token=NULL,lease_until=NULL WHERE encounter_id=? AND status IN ('PENDING','RUNNING','FAILED')",encounterId);
    }
    public void purgeDeletedSensitive(UUID encounterId){jdbc.update("UPDATE medication_order m SET name='삭제된 약',dose_text='삭제됨',frequency_text='삭제됨',instructions=NULL FROM extracted_item i WHERE m.source_item_id=i.id AND i.encounter_id=?",encounterId);jdbc.update("UPDATE extracted_item SET payload='{}'::jsonb,evidence='[]'::jsonb,review_reasons='{}',review_state=CASE WHEN review_state='APPLIED' THEN review_state ELSE 'DISMISSED' END,version=version+1 WHERE encounter_id=?",encounterId);jdbc.update("UPDATE encounter_revision SET summary='삭제된 기록',details='{}'::jsonb,evidence='[]'::jsonb,is_current=false WHERE encounter_id=?",encounterId);jdbc.update("UPDATE encounter_source SET extracted_text=NULL,text_version=text_version+1,status='FAILED' WHERE encounter_id=?",encounterId);}

    private List<Encounter> encounters(String where,Object...args){return jdbc.query("""
            SELECT e.id,e.group_id,e.created_by,u.display_name,e.record_type,e.occurred_on,e.hospital_name,e.title,
                   e.input_version,e.deleted_at,e.created_at,e.version FROM encounter e JOIN app_user u ON u.id=e.created_by
            """+where,this::mapEncounter,args);}
    private String sourceSelect(){return """
            SELECT s.id,s.group_id,s.encounter_id,s.asset_id,s.source_type,s.extracted_text,s.text_version,s.status,s.removed_at,s.created_at,
              f.id fid,f.group_id fgroup,f.uploaded_by,f.purpose,f.object_key,f.original_name,f.media_type,f.byte_size,f.sha256,f.state fstate,f.expires_at,f.deleted_at fdeleted,f.delete_attempts
            FROM encounter_source s JOIN file_asset f ON f.id=s.asset_id
            """;}
    private Encounter mapEncounter(ResultSet r,int n)throws SQLException{return new Encounter(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getObject(6,LocalDate.class),r.getString(7),r.getString(8),r.getInt(9),r.getTimestamp(10)==null?null:r.getTimestamp(10).toInstant(),r.getTimestamp(11).toInstant(),r.getLong(12));}
    private Asset mapAsset(ResultSet r,int n)throws SQLException{return new Asset(r.getObject("id",UUID.class),r.getObject("group_id",UUID.class),r.getObject("uploaded_by",UUID.class),r.getString("purpose"),r.getString("object_key"),r.getString("original_name"),r.getString("media_type"),r.getLong("byte_size"),r.getBytes("sha256"),r.getString("state"),instant(r,"expires_at"),instant(r,"deleted_at"),r.getInt("delete_attempts"));}
    private Source mapSource(ResultSet r,int n)throws SQLException{Asset a=new Asset(r.getObject("fid",UUID.class),r.getObject("fgroup",UUID.class),r.getObject("uploaded_by",UUID.class),r.getString("purpose"),r.getString("object_key"),r.getString("original_name"),r.getString("media_type"),r.getLong("byte_size"),r.getBytes("sha256"),r.getString("fstate"),instant(r,"expires_at"),instant(r,"fdeleted"),r.getInt("delete_attempts"));return new Source(r.getObject("id",UUID.class),r.getObject("group_id",UUID.class),r.getObject("encounter_id",UUID.class),r.getObject("asset_id",UUID.class),r.getString("source_type"),r.getString("extracted_text"),r.getInt("text_version"),r.getString("status"),instant(r,"removed_at"),r.getTimestamp("created_at").toInstant(),a);}
    private Job mapJob(ResultSet r,int n)throws SQLException{return new Job(r.getObject("id",UUID.class),r.getObject("group_id",UUID.class),r.getObject("encounter_id",UUID.class),r.getObject("source_id",UUID.class),r.getString("job_type"),r.getInt("input_version"),r.getString("dedup_key"),r.getString("status"),r.getInt("attempt_count"),r.getTimestamp("available_at").toInstant(),r.getObject("lease_token",UUID.class),instant(r,"lease_until"),r.getString("provider"),r.getString("model"),r.getString("prompt_version"),r.getString("error_code"),r.getTimestamp("created_at").toInstant());}
    private Revision mapRevision(ResultSet r,int n)throws SQLException{return new Revision(r.getObject("id",UUID.class),r.getObject("encounter_id",UUID.class),r.getInt("input_version"),r.getObject("job_id",UUID.class),r.getString("summary"),r.getString("details"),r.getString("evidence"),r.getBoolean("is_current"),r.getTimestamp("created_at").toInstant());}
    private ReviewItem mapReviewItem(ResultSet r,int n)throws SQLException{return new ReviewItem(r.getObject("id",UUID.class),r.getObject("encounter_id",UUID.class),r.getObject("revision_id",UUID.class),r.getString("item_type"),r.getString("payload"),r.getString("evidence"),r.getString("review_state"),(String[])r.getArray("review_reasons").getArray(),r.getObject("reviewed_by",UUID.class),r.getString("reviewer_name"),instant(r,"reviewed_at"),r.getLong("version"));}
    private Instant instant(ResultSet r,String column)throws SQLException{Timestamp value=r.getTimestamp(column);return value==null?null:value.toInstant();}
}
