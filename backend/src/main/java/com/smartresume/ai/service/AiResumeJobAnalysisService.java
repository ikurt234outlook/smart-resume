package com.smartresume.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.smartresume.ai.domain.AiResumeJobAnalysisEntity;
import com.smartresume.ai.domain.table.AiResumeJobAnalysisEntityTableDef;
import com.smartresume.ai.dto.AiDtos.AiResumeContent;
import com.smartresume.ai.dto.AiDtos.AiResumeRequirementMatch;
import com.smartresume.ai.dto.AiDtos.AiResumeSectionHeatmap;
import com.smartresume.ai.dto.AiInvocationRequest;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisCritique;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisListResponse;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisModelResult;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisRequest;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisResponse;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisSuggestionDraft;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisSummary;
import com.smartresume.ai.dto.suggestion.AiResumeSuggestion;
import com.smartresume.ai.dto.suggestion.AiResumeSuggestionPlan;
import com.smartresume.ai.dto.suggestion.ResumeSection;
import com.smartresume.ai.mapper.AiResumeJobAnalysisMapper;
import com.smartresume.ai.memory.AiConversationIdGenerator;
import com.smartresume.ai.memory.AiFeatureType;
import com.smartresume.common.exception.AppException;
import com.smartresume.common.security.CurrentUserContext;
import com.smartresume.resume.domain.ResumeEntity;
import com.smartresume.resume.service.ResumeContentService;
import com.smartresume.resume.service.ResumeLookupService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AiResumeJobAnalysisService {

    private static final String PROMPT_VERSION = "v1";
    private static final int DEFAULT_HISTORY_LIMIT = 10;
    private static final int MAX_HISTORY_LIMIT = 30;
    private static final int MAX_CRITIQUES = 5;
    private static final int MAX_QUESTIONS = 3;
    private static final String JOB_ANALYSIS_SYSTEM_PROMPT = """
        You are a professional resume reviewer evaluating one resume against one target job description.

        Output valid JSON only, matching the required schema. Write all user-facing text in Chinese.

        Rules:
        - Evaluate only facts present in the visible resume content and target JD. Never invent employers, dates, metrics, degrees, projects, skills, or credentials.
        - Return an integer score from 0 to 100 that reflects JD fit, not generic prestige.
        - Return 5-10 important JD requirements with matched, partial, or missing status, concise evidence, and a concrete recommendation.
        - Return a section heatmap only for visible and relevant resume sections.
        - Return 3-5 priority critiques. Each critique must include issue, rationale, recommendation, severity (high, medium, or low), and the affected section when known.
        - Return at most 6 directly applicable suggestions. A suggestion must target exactly one existing editor field using the provided section, field, and index conventions.
        - For personalSummary and personalInfo, omit index. For education, workExperience, projectExperience, skills, honors, and certificates, index is zero-based and must point to an existing item.
        - A suggestion's replacement text must preserve facts. When a fact or metric is missing, do not invent a replacement; put a concise question in questionsToImprove instead.
        - Do not produce a full Markdown resume, code fences, or generic filler.
        """;

    private final AiChatService aiChatService;
    private final ResumeLookupService resumeLookupService;
    private final ResumeContentService resumeContentService;
    private final AiResumeJobAnalysisMapper aiResumeJobAnalysisMapper;
    private final ObjectMapper objectMapper;

    public AiResumeJobAnalysisService(
        AiChatService aiChatService,
        ResumeLookupService resumeLookupService,
        ResumeContentService resumeContentService,
        AiResumeJobAnalysisMapper aiResumeJobAnalysisMapper,
        ObjectMapper objectMapper
    ) {
        this.aiChatService = aiChatService;
        this.resumeLookupService = resumeLookupService;
        this.resumeContentService = resumeContentService;
        this.aiResumeJobAnalysisMapper = aiResumeJobAnalysisMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiResumeJobAnalysisResponse analyze(AiResumeJobAnalysisRequest request) {
        long userId = CurrentUserContext.requireUserId();
        ResumeEntity resume = resumeLookupService.requireResume(request.resumeId(), userId);
        String jobDescription = request.jobDescription().trim();
        String visibleResumeContentJson = resumeContentService.buildAiVisibleContentJson(resume);
        AiResumeContent visibleResumeContent = parseVisibleResumeContent(visibleResumeContentJson);
        String analysisId = UUID.randomUUID().toString();

        AiInvocationRequest invocationRequest = new AiInvocationRequest(
            JOB_ANALYSIS_SYSTEM_PROMPT,
            buildUserMessage(visibleResumeContentJson, jobDescription),
            AiConversationIdGenerator.generate(resume.getId(), AiFeatureType.RESUME_JOB_ANALYSIS)
        );
        AiResumeJobAnalysisModelResult modelResult = aiChatService.callStructured(
            invocationRequest,
            AiResumeJobAnalysisModelResult.class
        );
        if (modelResult == null) {
            throw AppException.of(HttpStatus.INTERNAL_SERVER_ERROR, "error.ai.resumeJobAnalysisEmpty");
        }

        AiResumeJobAnalysisResponse response = toResponse(
            analysisId,
            modelResult,
            visibleResumeContent,
            false,
            Instant.now().toString()
        );
        LocalDateTime now = LocalDateTime.now();
        AiResumeJobAnalysisEntity entity = new AiResumeJobAnalysisEntity();
        entity.setId(analysisId);
        entity.setUserId(userId);
        entity.setResumeId(resume.getId());
        entity.setJobDescription(jobDescription);
        entity.setResumeContentHash(hash(visibleResumeContentJson));
        entity.setPromptVersion(PROMPT_VERSION);
        entity.setResultJson(toJson(response));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        aiResumeJobAnalysisMapper.insert(entity);
        return response;
    }

    public AiResumeJobAnalysisResponse get(String resumeId, String analysisId) {
        long userId = CurrentUserContext.requireUserId();
        ResumeEntity resume = resumeLookupService.requireResume(resumeId, userId);
        return toResponse(requireOwnedAnalysis(resume.getId(), analysisId, userId), resume);
    }

    public AiResumeJobAnalysisListResponse list(String resumeId, int limit) {
        long userId = CurrentUserContext.requireUserId();
        ResumeEntity resume = resumeLookupService.requireResume(resumeId, userId);
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        AiResumeJobAnalysisEntityTableDef table = AiResumeJobAnalysisEntityTableDef.AI_RESUME_JOB_ANALYSIS_ENTITY;
        QueryWrapper query = QueryWrapper.create()
            .where(table.USER_ID.eq(userId))
            .and(table.RESUME_ID.eq(resume.getId()))
            .orderBy(table.CREATED_AT, false);
        List<AiResumeJobAnalysisSummary> items = aiResumeJobAnalysisMapper.selectListByQuery(query).stream()
            .limit(safeLimit)
            .map(entity -> toSummary(entity, resume))
            .toList();
        return new AiResumeJobAnalysisListResponse(items);
    }

    public int defaultHistoryLimit() {
        return DEFAULT_HISTORY_LIMIT;
    }

    private AiResumeJobAnalysisEntity requireOwnedAnalysis(String resumeId, String analysisId, long userId) {
        AiResumeJobAnalysisEntity entity = aiResumeJobAnalysisMapper.selectOneById(analysisId);
        if (
            entity == null
                || !Long.valueOf(userId).equals(entity.getUserId())
                || !resumeId.equals(entity.getResumeId())
        ) {
            throw AppException.of(HttpStatus.NOT_FOUND, "error.ai.resumeJobAnalysisNotFound");
        }
        return entity;
    }

    private AiResumeJobAnalysisResponse toResponse(AiResumeJobAnalysisEntity entity, ResumeEntity resume) {
        AiResumeJobAnalysisResponse stored = fromJson(entity.getResultJson(), AiResumeJobAnalysisResponse.class);
        boolean stale = !hash(resumeContentService.buildAiVisibleContentJson(resume)).equals(entity.getResumeContentHash());
        return new AiResumeJobAnalysisResponse(
            entity.getId(),
            stored.score(),
            stored.summary(),
            stored.firstImpression(),
            normalizeList(stored.requirementMatches()),
            normalizeList(stored.sectionHeatmap()),
            normalizeList(stored.critiques()),
            normalizeSuggestionPlan(stored.suggestionPlan()),
            normalizeList(stored.questionsToImprove()),
            stale,
            stored.generatedAt()
        );
    }

    private AiResumeJobAnalysisSummary toSummary(AiResumeJobAnalysisEntity entity, ResumeEntity resume) {
        AiResumeJobAnalysisResponse response = toResponse(entity, resume);
        return new AiResumeJobAnalysisSummary(
            response.analysisId(),
            entity.getJobDescription(),
            response.score(),
            response.summary(),
            response.stale(),
            response.generatedAt()
        );
    }

    private AiResumeJobAnalysisResponse toResponse(
        String analysisId,
        AiResumeJobAnalysisModelResult modelResult,
        AiResumeContent visibleResumeContent,
        boolean stale,
        String generatedAt
    ) {
        return new AiResumeJobAnalysisResponse(
            analysisId,
            clampScore(modelResult.score()),
            requireGeneratedText(modelResult.summary()),
            requireGeneratedText(modelResult.firstImpression()),
            normalizeRequirementMatches(modelResult.requirementMatches()),
            normalizeSectionHeatmap(modelResult.sectionHeatmap()),
            normalizeCritiques(modelResult.critiques()),
            toSuggestionPlan(analysisId, modelResult.suggestions(), modelResult.suggestionSummary(), visibleResumeContent),
            normalizeQuestions(modelResult.questionsToImprove()),
            stale,
            generatedAt
        );
    }

    private String buildUserMessage(String visibleResumeContentJson, String jobDescription) {
        return "Target job description:\n" + jobDescription + "\n\nVisible resume content JSON:\n" + visibleResumeContentJson;
    }

    private AiResumeContent parseVisibleResumeContent(String visibleResumeContentJson) {
        try {
            return objectMapper.readValue(visibleResumeContentJson, AiResumeContent.class);
        } catch (JsonProcessingException exception) {
            throw AppException.of(HttpStatus.INTERNAL_SERVER_ERROR, "error.ai.resumeJobAnalysisParseFailed");
        }
    }

    private AiResumeSuggestionPlan toSuggestionPlan(
        String analysisId,
        List<AiResumeJobAnalysisSuggestionDraft> drafts,
        String summary,
        AiResumeContent visibleResumeContent
    ) {
        List<AiResumeJobAnalysisSuggestionDraft> applicableDrafts = normalizeList(drafts).stream()
            .filter(draft -> isApplicableSuggestion(draft, visibleResumeContent))
            .limit(6)
            .toList();
        List<AiResumeSuggestion> suggestions = IntStream.range(0, applicableDrafts.size())
            .mapToObj(index -> {
                AiResumeJobAnalysisSuggestionDraft draft = applicableDrafts.get(index);
                return new AiResumeSuggestion(
                analysisId + "-s" + index,
                draft.section(),
                draft.section() == ResumeSection.personalInfo || draft.section().isScalar() ? null : draft.index(),
                draft.field().trim(),
                trimOrNull(draft.currentValue()),
                draft.suggestedValue().trim(),
                draft.rationale().trim(),
                "pending"
                );
            })
            .toList();
        return new AiResumeSuggestionPlan(suggestions, trimOrNull(summary));
    }

    private boolean isApplicableSuggestion(AiResumeJobAnalysisSuggestionDraft draft, AiResumeContent content) {
        if (
            draft == null
                || draft.section() == null
                || !StringUtils.hasText(draft.field())
                || !draft.section().isFieldAllowed(draft.field().trim())
                || !StringUtils.hasText(draft.suggestedValue())
                || !StringUtils.hasText(draft.rationale())
        ) {
            return false;
        }
        if (draft.section() == ResumeSection.personalInfo || draft.section().isScalar()) {
            return draft.index() == null;
        }
        return draft.index() != null && draft.index() >= 0 && draft.index() < itemCount(content, draft.section());
    }

    private int itemCount(AiResumeContent content, ResumeSection section) {
        return switch (section) {
            case education -> normalizeList(content.education()).size();
            case workExperience -> normalizeList(content.workExperience()).size();
            case projectExperience -> normalizeList(content.projectExperience()).size();
            case skills -> normalizeList(content.skills()).size();
            case honors -> normalizeList(content.honors()).size();
            case certificates -> normalizeList(content.certificates()).size();
            case personalInfo, personalSummary -> 0;
        };
    }

    private List<AiResumeRequirementMatch> normalizeRequirementMatches(List<AiResumeRequirementMatch> matches) {
        return normalizeList(matches).stream()
            .filter(match -> match != null && StringUtils.hasText(match.text()))
            .limit(10)
            .map(match -> new AiResumeRequirementMatch(
                match.text().trim(),
                trimOrEmpty(match.category()),
                trimOrEmpty(match.importance()),
                trimOrEmpty(match.status()),
                clampScore(match.score()),
                normalizeList(match.matchedSections()),
                normalizeList(match.evidence()).stream().filter(StringUtils::hasText).limit(3).map(String::trim).toList(),
                trimOrEmpty(match.suggestion())
            ))
            .toList();
    }

    private List<AiResumeSectionHeatmap> normalizeSectionHeatmap(List<AiResumeSectionHeatmap> sections) {
        return normalizeList(sections).stream()
            .filter(section -> section != null && StringUtils.hasText(section.sectionKey()))
            .map(section -> new AiResumeSectionHeatmap(
                section.sectionKey().trim(),
                trimOrEmpty(section.sectionLabel()),
                clampScore(section.score()),
                trimOrEmpty(section.status()),
                Math.max(0, section.matchedCount()),
                Math.max(0, section.missingCount()),
                trimOrEmpty(section.summary())
            ))
            .toList();
    }

    private List<AiResumeJobAnalysisCritique> normalizeCritiques(List<AiResumeJobAnalysisCritique> critiques) {
        return normalizeList(critiques).stream()
            .filter(critique -> critique != null && StringUtils.hasText(critique.issue()))
            .limit(MAX_CRITIQUES)
            .map(critique -> new AiResumeJobAnalysisCritique(
                normalizeSeverity(critique.severity()),
                trimOrEmpty(critique.section()),
                critique.index() == null || critique.index() < 0 ? null : critique.index(),
                critique.issue().trim(),
                trimOrEmpty(critique.rationale()),
                trimOrEmpty(critique.recommendation())
            ))
            .toList();
    }

    private List<String> normalizeQuestions(List<String> questions) {
        return normalizeList(questions).stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .limit(MAX_QUESTIONS)
            .toList();
    }

    private AiResumeSuggestionPlan normalizeSuggestionPlan(AiResumeSuggestionPlan plan) {
        if (plan == null) {
            return new AiResumeSuggestionPlan(List.of(), null);
        }
        return new AiResumeSuggestionPlan(normalizeList(plan.suggestions()), trimOrNull(plan.summary()));
    }

    private String requireGeneratedText(String value) {
        if (!StringUtils.hasText(value)) {
            throw AppException.of(HttpStatus.INTERNAL_SERVER_ERROR, "error.ai.resumeJobAnalysisEmpty");
        }
        return value.trim();
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(score, 100));
    }

    private String normalizeSeverity(String value) {
        String normalized = trimOrEmpty(value).toLowerCase();
        return switch (normalized) {
            case "high", "medium", "low" -> normalized;
            default -> "medium";
        };
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw AppException.of(HttpStatus.INTERNAL_SERVER_ERROR, "error.ai.resumeJobAnalysisSerializeFailed");
        }
    }

    private <T> T fromJson(String value, Class<T> targetType) {
        try {
            return objectMapper.readValue(value, targetType);
        } catch (JsonProcessingException exception) {
            throw AppException.of(HttpStatus.INTERNAL_SERVER_ERROR, "error.ai.resumeJobAnalysisParseFailed");
        }
    }

    private <T> List<T> normalizeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String trimOrEmpty(String value) {
        String trimmed = trimOrNull(value);
        return trimmed == null ? "" : trimmed;
    }
}
