package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interview.agent.converter.*;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话实体类
 *
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sessions", indexes = {
        @Index(name = "idx_sessions_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_sessions_user_updated", columnList = "user_id, updated_at"),
        @Index(name = "idx_sessions_user_pinned", columnList = "user_id, pinned, pinned_at")
})
public class Session {

    @Id
    private String id;

    @Column(length = 200)
    private String title;

    @Column(name = "session_type", length = 30)
    @JsonProperty("session_type")
    private String sessionType;

    @Column(name = "user_id", nullable = false)
    @JsonProperty("user_id")
    private String userId;

    @Convert(converter = JDAnalysisConverter.class)
    @Column(columnDefinition = "MEDIUMTEXT")
    @JsonProperty("jd_analysis")
    private JDAnalysis jdAnalysis;

    @Convert(converter = ResumeConverter.class)
    @Column(columnDefinition = "MEDIUMTEXT")
    private Resume resume;

    @Convert(converter = ResumeMatchResultConverter.class)
    @Column(columnDefinition = "MEDIUMTEXT")
    @JsonProperty("match_result")
    private ResumeMatchResult matchResult;

    @Convert(converter = QuestionPlanConverter.class)
    @Column(columnDefinition = "MEDIUMTEXT")
    @JsonProperty("question_plan")
    private QuestionPlan questionPlan;

    @Convert(converter = InterviewStateConverter.class)
    @Column(columnDefinition = "MEDIUMTEXT")
    @JsonProperty("interview_state")
    private InterviewState interviewState;

    @Convert(converter = EvaluationReportConverter.class)
    @Column(columnDefinition = "MEDIUMTEXT")
    private EvaluationReport report;

    @Convert(converter = ReviewPlanConverter.class)
    @Column(columnDefinition = "MEDIUMTEXT")
    @JsonProperty("review_plan")
    private ReviewPlan reviewPlan;

    @Convert(converter = ConversationMessagesConverter.class)
    @Column(name = "chat_messages", columnDefinition = "MEDIUMTEXT")
    @JsonProperty("chat_messages")
    private List<ConversationMessage> chatMessages;

    @Builder.Default
    @Column
    private Boolean pinned = false;

    @Column(name = "pinned_at")
    @JsonProperty("pinned_at")
    private LocalDateTime pinnedAt;

    @Column(nullable = false, length = 50)
    private String status;  // init/jd_analyzed/resume_matched/planned/interviewing/terminated/evaluated/completed

    @Column(name = "created_at", nullable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 会话状态常量
     */
    public static final String STATUS_INIT = "init";
    public static final String STATUS_JD_ANALYZED = "jd_analyzed";
    public static final String STATUS_RESUME_MATCHED = "resume_matched";
    public static final String STATUS_PLANNED = "planned";
    public static final String STATUS_INTERVIEWING = "interviewing";
    public static final String STATUS_TERMINATED = "terminated";
    public static final String STATUS_EVALUATED = "evaluated";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CHAT = "chat";

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_INTERVIEW = "interview";

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (sessionType == null) {
            sessionType = TYPE_INTERVIEW;
        }
        if (pinned == null) {
            pinned = false;
        }
        if (pinned && pinnedAt == null) {
            pinnedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (pinned == null) {
            pinned = false;
        }
        updatedAt = LocalDateTime.now();
    }
}
