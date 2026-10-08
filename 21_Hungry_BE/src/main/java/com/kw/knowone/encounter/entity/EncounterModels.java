package com.kw.knowone.encounter.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class EncounterModels {
    private EncounterModels(){ }
    public record Encounter(UUID id,UUID groupId,UUID createdBy,String creatorName,String recordType,LocalDate occurredOn,
            String hospitalName,String title,int inputVersion,Instant deletedAt,Instant createdAt,long version){ }
    public record Asset(UUID id,UUID groupId,UUID uploadedBy,String purpose,String objectKey,String originalName,
            String mediaType,long byteSize,byte[] sha256,String state,Instant expiresAt,Instant deletedAt,int deleteAttempts){ }
    public record Source(UUID id,UUID groupId,UUID encounterId,UUID assetId,String sourceType,String documentType,String extractedText,
            int textVersion,String status,Instant removedAt,Instant createdAt,Asset asset){ }
    public record Job(UUID id,UUID groupId,UUID encounterId,UUID sourceId,String jobType,int inputVersion,String dedupKey,
            String status,int attemptCount,Instant availableAt,UUID leaseToken,Instant leaseUntil,String provider,String model,
            String promptVersion,String errorCode,Instant createdAt){ }
    public record Revision(UUID id,UUID encounterId,int inputVersion,UUID jobId,String summary,String details,String evidence,
            boolean current,Instant createdAt){ }
    public record ReviewItem(UUID id,UUID encounterId,UUID revisionId,String itemType,String payload,String evidence,
            String reviewState,String[] reviewReasons,UUID reviewedBy,String reviewerName,Instant reviewedAt,long version){ }
    public record DeletionImpact(java.util.List<UUID> sourceIds,java.util.List<UUID> cancelOccurrenceIds,
            java.util.List<UUID> retainSharedOccurrenceIds,java.util.List<UUID> medicationIds,java.util.List<String> fingerprintParts){ }
}
