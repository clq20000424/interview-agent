package com.interview.agent.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    @JsonProperty("user_id")
    private String userId;

    private String name;

    @JsonProperty("skill_level")
    @Builder.Default
    private Map<String, String> skillLevel = new HashMap<>();  // skill -> beginner/intermediate/advanced

    @JsonProperty("weak_points")
    @Builder.Default
    private List<WeakPoint> weakPoints = new ArrayList<>();

    @JsonProperty("interview_hist")
    @Builder.Default
    private List<InterviewRecord> interviewHist = new ArrayList<>();

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeakPoint {
        private String topic;
        private double score;

        @JsonProperty("hit_count")
        private int hitCount;

        @JsonProperty("wrong_count")
        private int wrongCount;

        @JsonProperty("last_seen")
        private LocalDateTime lastSeen;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterviewRecord {
        @JsonProperty("session_id")
        private String sessionId;
        private String position;
        @JsonProperty("overall_score")
        private double overallScore;
        private LocalDateTime date;
    }
}
