package com.kw.knowone.encounter.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.kw.knowone.group.dto.GroupDtos.UserRef;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.JsonNode;

public final class EncounterDtos {
    private EncounterDtos(){ }
    public record CreateRequest(@NotBlank String recordType,@NotBlank @Size(max=150)String title,LocalDate occurredOn,
            @Size(max=150)String hospitalName){ }
    public record UpdateRequest(@NotNull Long expectedVersion,JsonNode title,JsonNode occurredOn,JsonNode hospitalName){ }
    public record DocumentMetadata(@NotBlank String documentType){ }
    public record UploadMetadata(@NotNull Long expectedVersion,@NotNull Integer expectedInputVersion,
            List<@Valid DocumentMetadata> documents){
        public UploadMetadata(Long expectedVersion,Integer expectedInputVersion){this(expectedVersion,expectedInputVersion,
                List.of(new DocumentMetadata("DIAGNOSIS")));}
    }
    public record TextUpdate(@NotNull Integer expectedInputVersion,@NotNull Integer expectedTextVersion,@NotBlank String text){ }
    public record VersionRequest(@NotNull Long expectedVersion,@NotNull Integer expectedInputVersion){ }
    public record RetryRequest(@NotNull Integer expectedInputVersion){ }
    public record DeletionPreviewRequest(@NotNull Long expectedVersion){ }
    public record DeleteRequest(@NotNull Long expectedVersion,@NotBlank String previewToken){ }

    public record Encounter(UUID id,UUID groupId,String recordType,String title,LocalDate occurredOn,String hospitalName,
            UserRef createdBy,int inputVersion,long version,Instant createdAt,String processingState,
            boolean hasReviewItems,boolean isSummaryStale){ }
    public record FileRef(UUID id,String originalName,String mediaType,long byteSize,String state,Integer pageCount){ }
    public record Source(UUID id,UUID encounterId,String sourceType,String status,int textVersion,Instant removedAt,
            FileRef file,String contentPath,String documentType){ }
    public record JobError(String code,String message,boolean retryable,String userAction){ }
    public record Job(UUID id,UUID encounterId,UUID sourceId,String jobType,int inputVersion,String status,int attemptCount,
            JobError error,boolean canRetry){ }
    public record Summary(UUID revisionId,int inputVersion,String text,Object details,Object evidence){ }
    public record Detail(UUID id,UUID groupId,String recordType,String title,LocalDate occurredOn,String hospitalName,
            UserRef createdBy,int inputVersion,long version,Instant createdAt,String processingState,
            boolean hasReviewItems,boolean isSummaryStale,Summary summary,List<Source> sources,List<Job> jobs,
            List<Object> reviewItems,List<Object> linkedTasks,boolean linkedTasksHasMore){ }
    public record Page<T>(List<T> items,String nextCursor,boolean hasMore){ }
    public record UploadResponse(UUID encounterId,int inputVersion,long version,List<Source> sources,List<Job> jobs,int activeImageCount){ }
    public record AudioUploadResponse(UUID encounterId,int inputVersion,long version,Source source,Job job){ }
    public record Text(UUID sourceId,String sourceType,String status,int textVersion,String text){ }
    public record TextUpdated(UUID sourceId,int textVersion,int inputVersion,long encounterVersion,UUID analysisJobId){ }
    public record SourceRemoved(UUID encounterId,UUID sourceId,int inputVersion,long version,String fileState,UUID analysisJobId){ }
    public record Items<T>(List<T> items){ }
    public record DeletionPreview(String previewToken,Instant expiresAt,long encounterVersion,List<UUID> removeSourceIds,
            List<UUID> cancelOccurrenceIds,List<UUID> retainSharedOccurrenceIds,List<UUID> removeMedicationIds,
            boolean preserveCompletedHistory){ }
    public record DeletionAccepted(UUID encounterId,boolean deletionAccepted,boolean fileDeletionPending){ }
    public record Binary(String originalName,String mediaType,long byteSize,java.io.InputStream input){ }
}
