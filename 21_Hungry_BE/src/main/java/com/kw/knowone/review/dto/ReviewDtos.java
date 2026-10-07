package com.kw.knowone.review.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import com.kw.knowone.task.dto.TaskDtos;

public final class ReviewDtos {
    private ReviewDtos(){ }
    public record ReviewItem(UUID id,UUID encounterId,UUID revisionId,String itemType,String reviewState,
            List<String> reviewReasons,JsonNode payload,JsonNode evidence,TaskDtos.UserRef reviewedBy,
            OffsetDateTime reviewedAt,long version){ }
    public record ReviewItems(List<ReviewItem> items,int inputVersion,UUID revisionId,boolean isAnalysisCurrent){ }
    public record ConfirmRequest(@NotNull Integer expectedInputVersion,@NotNull UUID revisionId,
            @NotEmpty List<@Valid ConfirmItem> items){ }
    public record ConfirmItem(@NotNull UUID itemId,@NotNull @Min(0)Long expectedVersion,JsonNode payload,JsonNode conflictResolution){ }
    public record Confirmed(List<UUID> appliedItemIds,List<TaskDtos.Medication> medications,List<UUID> seriesIds,
            List<TaskDtos.Task> occurrences,int inputVersion){ }
    public record DismissRequest(@NotNull @Min(0)Long expectedVersion){ }
    public record Dismissed(UUID id,String reviewState,long version){ }
    public record MedicationPage(List<TaskDtos.Medication> items,String nextCursor,boolean hasMore){ }
}
