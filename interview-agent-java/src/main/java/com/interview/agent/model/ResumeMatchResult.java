package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeMatchResult {

    @JsonProperty("overall_score")
    private double overallScore;

    @JsonProperty("skill_match")
    private List<SkillMatch> skillMatch;

    private List<String> strengths;
    private List<String> weaknesses;

    @JsonProperty("focus_areas")
    private List<String> focusAreas;

    @JsonProperty("resume_gaps")
    private List<String> resumeGaps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillMatch {
        @JsonProperty("skill_name")
        private String skillName;

        private boolean required;
        private boolean matched;

        @JsonProperty("match_score")
        private double matchScore;

        private String evidence;
    }
}
