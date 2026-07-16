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

    /**
     * 总得分
     */
    @JsonProperty("overall_score")
    private double overallScore;

    /**
     * 技能匹配
     */
    @JsonProperty("skill_match")
    private List<SkillMatch> skillMatch;

    /**
     * 优势
     */
    private List<String> strengths;

    /**
     * 短板
     */

    private List<String> weaknesses;

    /**
     * 重点领域
     */
    @JsonProperty("focus_areas")
    private List<String> focusAreas;

    /**
     * 简历短板
     */
    @JsonProperty("resume_gaps")
    private List<String> resumeGaps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillMatch {
        /**
         * 技能名称
         */
        @JsonProperty("skill_name")
        private String skillName;

        /**
         * 是否必需
         */
        private boolean required;

        /**
         * 是否匹配
         */
        private boolean matched;

        /**
         * 匹配得分
         */
        @JsonProperty("match_score")
        private double matchScore;

        /**
         * 证据
         */
        private String evidence;
    }
}
