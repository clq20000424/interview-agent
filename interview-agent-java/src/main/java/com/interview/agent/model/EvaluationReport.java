package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationReport {

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("candidate_name")
    private String candidateName;

    private String position;

    @JsonProperty("overall_score")
    private double overallScore;

    @JsonProperty("overall_level")
    private String overallLevel;    // A/B/C/D

    @JsonProperty("dimension_score")
    private Map<String, Double> dimensionScore;

    private List<String> strengths;
    private List<String> weaknesses;

    @JsonProperty("detailed_review")
    private List<QuestionReview> detailedReview;

    private String summary;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionReview {
        @JsonProperty("question_content")
        private String questionContent;

        @JsonProperty("user_answer")
        private String userAnswer;

        private double score;
        private String comment;

        @JsonProperty("key_points_hit")
        private List<String> keyPointsHit;

        @JsonProperty("key_points_missed")
        private List<String> keyPointsMissed;
    }
}
