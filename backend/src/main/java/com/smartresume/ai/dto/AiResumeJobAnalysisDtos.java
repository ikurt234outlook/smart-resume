package com.smartresume.ai.dto;

import com.smartresume.ai.dto.AiDtos.AiResumeRequirementMatch;
import com.smartresume.ai.dto.AiDtos.AiResumeSectionHeatmap;
import com.smartresume.ai.dto.suggestion.AiResumeSuggestionPlan;
import com.smartresume.ai.dto.suggestion.ResumeSection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AiResumeJobAnalysisDtos {

    private AiResumeJobAnalysisDtos() {
    }

    public record AiResumeJobAnalysisRequest(
        @NotBlank(message = "{validation.ai.resumeIdRequired}")
        String resumeId,
        @NotBlank(message = "{validation.ai.jobDescriptionRequired}")
        @Size(max = 30000, message = "{validation.ai.jobDescriptionTooLong}")
        String jobDescription
    ) {
    }

    public record AiResumeJobAnalysisSuggestionDraft(
        ResumeSection section,
        Integer index,
        String field,
        String currentValue,
        String suggestedValue,
        String rationale
    ) {
    }

    public record AiResumeJobAnalysisCritique(
        String severity,
        String section,
        Integer index,
        String issue,
        String rationale,
        String recommendation
    ) {
    }

    public record AiResumeJobAnalysisModelResult(
        int score,
        String summary,
        String firstImpression,
        List<AiResumeRequirementMatch> requirementMatches,
        List<AiResumeSectionHeatmap> sectionHeatmap,
        List<AiResumeJobAnalysisCritique> critiques,
        List<AiResumeJobAnalysisSuggestionDraft> suggestions,
        String suggestionSummary,
        List<String> questionsToImprove
    ) {
    }

    public record AiResumeJobAnalysisResponse(
        String analysisId,
        int score,
        String summary,
        String firstImpression,
        List<AiResumeRequirementMatch> requirementMatches,
        List<AiResumeSectionHeatmap> sectionHeatmap,
        List<AiResumeJobAnalysisCritique> critiques,
        AiResumeSuggestionPlan suggestionPlan,
        List<String> questionsToImprove,
        boolean stale,
        String generatedAt
    ) {
    }

    public record AiResumeJobAnalysisSummary(
        String analysisId,
        String jobDescription,
        int score,
        String summary,
        boolean stale,
        String generatedAt
    ) {
    }

    public record AiResumeJobAnalysisListResponse(
        @NotNull List<AiResumeJobAnalysisSummary> items
    ) {
    }
}
