package com.smartresume.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartresume.ai.domain.AiResumeJobAnalysisEntity;
import com.smartresume.ai.dto.AiDtos.AiResumeRequirementMatch;
import com.smartresume.ai.dto.AiDtos.AiResumeSectionHeatmap;
import com.smartresume.ai.dto.AiInvocationRequest;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisCritique;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisModelResult;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisRequest;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisResponse;
import com.smartresume.ai.dto.AiResumeJobAnalysisDtos.AiResumeJobAnalysisSuggestionDraft;
import com.smartresume.ai.dto.suggestion.ResumeSection;
import com.smartresume.ai.mapper.AiResumeJobAnalysisMapper;
import com.smartresume.common.exception.AppException;
import com.smartresume.common.security.CurrentUserContext;
import com.smartresume.resume.domain.ResumeEntity;
import com.smartresume.resume.service.ResumeContentService;
import com.smartresume.resume.service.ResumeLookupService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiResumeJobAnalysisServiceTest {

    @Mock
    private AiChatService aiChatService;

    @Mock
    private ResumeLookupService resumeLookupService;

    @Mock
    private ResumeContentService resumeContentService;

    @Mock
    private AiResumeJobAnalysisMapper aiResumeJobAnalysisMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiResumeJobAnalysisService service;

    @BeforeEach
    void setUp() {
        CurrentUserContext.set(new CurrentUserContext.AuthenticatedUser(7L, "tester", false));
        service = new AiResumeJobAnalysisService(
            aiChatService,
            resumeLookupService,
            resumeContentService,
            aiResumeJobAnalysisMapper,
            objectMapper
        );

        ResumeEntity resume = new ResumeEntity();
        resume.setId("resume-1");
        resume.setUserId(7L);
        lenient().when(resumeLookupService.requireResume(anyString(), anyLong())).thenReturn(resume);
        lenient().when(resumeContentService.buildAiVisibleContentJson(any(ResumeEntity.class)))
            .thenReturn(visibleResumeContentJson());
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    @Test
    void createsPersistedAnalysisFromVisibleResumeContent() {
        when(aiChatService.callStructured(any(AiInvocationRequest.class), eq(AiResumeJobAnalysisModelResult.class)))
            .thenReturn(modelResult(List.of(validDraft())));

        AiResumeJobAnalysisResponse response = service.analyze(new AiResumeJobAnalysisRequest(
            "resume-1",
            " Looking for a Java backend engineer with Spring Boot experience. "
        ));

        assertThat(response.score()).isEqualTo(84);
        assertThat(response.stale()).isFalse();
        assertThat(response.suggestionPlan().suggestions()).hasSize(1);
        assertThat(response.suggestionPlan().suggestions().getFirst().status()).isEqualTo("pending");

        ArgumentCaptor<AiInvocationRequest> invocationCaptor = ArgumentCaptor.forClass(AiInvocationRequest.class);
        verify(aiChatService).callStructured(invocationCaptor.capture(), eq(AiResumeJobAnalysisModelResult.class));
        assertThat(invocationCaptor.getValue().userMessage())
            .contains("Visible resume content JSON")
            .contains("Visible summary")
            .doesNotContain("hiddenSections")
            .doesNotContain("avatar-url");
        assertThat(invocationCaptor.getValue().conversationId())
            .startsWith("resume-1_resume_job_analysis_");

        ArgumentCaptor<AiResumeJobAnalysisEntity> entityCaptor = ArgumentCaptor.forClass(AiResumeJobAnalysisEntity.class);
        verify(aiResumeJobAnalysisMapper).insert(entityCaptor.capture());
        AiResumeJobAnalysisEntity entity = entityCaptor.getValue();
        assertThat(entity.getUserId()).isEqualTo(7L);
        assertThat(entity.getResumeId()).isEqualTo("resume-1");
        assertThat(entity.getJobDescription()).isEqualTo("Looking for a Java backend engineer with Spring Boot experience.");
        assertThat(entity.getResumeContentHash()).hasSize(64);
        assertThat(entity.getResultJson()).contains(response.analysisId());
    }

    @Test
    void filtersInvalidSuggestionsAndGeneratesUniqueIdsForDuplicateDrafts() {
        AiResumeJobAnalysisSuggestionDraft duplicate = validDraft();
        List<AiResumeJobAnalysisSuggestionDraft> drafts = List.of(
            duplicate,
            duplicate,
            new AiResumeJobAnalysisSuggestionDraft(
                ResumeSection.personalInfo,
                0,
                "headline",
                "Backend engineer",
                "Senior backend engineer",
                "Clarifies seniority."
            ),
            new AiResumeJobAnalysisSuggestionDraft(
                ResumeSection.workExperience,
                8,
                "description",
                "",
                "Improved service reliability.",
                "Targets an unavailable item."
            ),
            new AiResumeJobAnalysisSuggestionDraft(
                ResumeSection.personalInfo,
                null,
                "avatar",
                "",
                "https://example.com/avatar.png",
                "Targets an unsupported field."
            )
        );
        when(aiChatService.callStructured(any(AiInvocationRequest.class), eq(AiResumeJobAnalysisModelResult.class)))
            .thenReturn(modelResult(drafts));

        AiResumeJobAnalysisResponse response = service.analyze(new AiResumeJobAnalysisRequest("resume-1", "Target JD"));

        assertThat(response.suggestionPlan().suggestions()).hasSize(2);
        assertThat(response.suggestionPlan().suggestions())
            .extracting(suggestion -> suggestion.id())
            .doesNotHaveDuplicates()
            .allMatch(id -> id.startsWith(response.analysisId() + "-s"));
    }

    @Test
    void marksStoredAnalysisStaleWhenVisibleResumeContentChanges() {
        when(aiChatService.callStructured(any(AiInvocationRequest.class), eq(AiResumeJobAnalysisModelResult.class)))
            .thenReturn(modelResult(List.of(validDraft())));
        when(resumeContentService.buildAiVisibleContentJson(any(ResumeEntity.class)))
            .thenReturn(visibleResumeContentJson(), visibleResumeContentJson().replace("Visible summary", "Updated summary"));

        service.analyze(new AiResumeJobAnalysisRequest("resume-1", "Target JD"));

        ArgumentCaptor<AiResumeJobAnalysisEntity> entityCaptor = ArgumentCaptor.forClass(AiResumeJobAnalysisEntity.class);
        verify(aiResumeJobAnalysisMapper).insert(entityCaptor.capture());
        when(aiResumeJobAnalysisMapper.selectOneById(entityCaptor.getValue().getId())).thenReturn(entityCaptor.getValue());

        AiResumeJobAnalysisResponse response = service.get("resume-1", entityCaptor.getValue().getId());

        assertThat(response.stale()).isTrue();
    }

    @Test
    void rejectsAnalysisOwnedByAnotherUser() {
        AiResumeJobAnalysisEntity entity = new AiResumeJobAnalysisEntity();
        entity.setId("analysis-1");
        entity.setUserId(8L);
        entity.setResumeId("resume-1");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        when(aiResumeJobAnalysisMapper.selectOneById("analysis-1")).thenReturn(entity);

        assertThatThrownBy(() -> service.get("resume-1", "analysis-1"))
            .isInstanceOf(AppException.class)
            .extracting(exception -> ((AppException) exception).getMessageKey())
            .isEqualTo("error.ai.resumeJobAnalysisNotFound");

        verify(aiChatService, never()).callStructured(any(AiInvocationRequest.class), eq(AiResumeJobAnalysisModelResult.class));
    }

    private AiResumeJobAnalysisModelResult modelResult(List<AiResumeJobAnalysisSuggestionDraft> drafts) {
        return new AiResumeJobAnalysisModelResult(
            84,
            "The resume has a credible backend foundation for this role.",
            "Relevant engineering experience is visible, but outcomes need clearer evidence.",
            List.of(new AiResumeRequirementMatch(
                "Spring Boot",
                "skill",
                "high",
                "matched",
                90,
                List.of("workExperience", "skills"),
                List.of("Built Spring Boot services."),
                "Keep the Spring Boot experience explicit."
            )),
            List.of(new AiResumeSectionHeatmap(
                "workExperience",
                "Work experience",
                82,
                "strong",
                1,
                0,
                "The current experience supports the target role."
            )),
            List.of(new AiResumeJobAnalysisCritique(
                "medium",
                "workExperience",
                0,
                "Impact is not quantified.",
                "The JD asks for delivery outcomes.",
                "Add verified scale or result details."
            )),
            drafts,
            "Apply the verified wording improvements first.",
            List.of("What production traffic or reliability metrics can you verify?")
        );
    }

    private AiResumeJobAnalysisSuggestionDraft validDraft() {
        return new AiResumeJobAnalysisSuggestionDraft(
            ResumeSection.workExperience,
            0,
            "description",
            "Built Spring Boot services.",
            "Built Spring Boot services that supported the platform's core delivery workflows.",
            "Makes the relevant engineering scope clearer without adding unsupported metrics."
        );
    }

    private String visibleResumeContentJson() {
        return """
            {"personalInfo":{"fullName":"Alex Chen","headline":"Backend Engineer","phone":"13800000000","email":"alex@example.com","city":"Shanghai","website":"https://alex.dev","expectedSalary":"","age":"","avatar":null},"personalSummary":"Visible summary","education":[],"workExperience":[{"company":"Example Corp","role":"Backend Engineer","startDate":"2020","endDate":"2024","description":"Built Spring Boot services."}],"projectExperience":[],"skills":[{"name":"Java","level":"Advanced"}],"honors":[],"certificates":[]}
            """.trim();
    }
}
