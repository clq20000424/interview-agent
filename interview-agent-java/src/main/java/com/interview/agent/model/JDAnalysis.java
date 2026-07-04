package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JDAnalysis {

    @JsonProperty("raw_jd")
    private String rawJD;

    private String position;
    private String company;

    @JsonProperty("required_skills")
    private List<SkillItem> requiredSkills;

    @JsonProperty("preferred_skills")
    private List<SkillItem> preferredSkills;

    @JsonProperty("experience_level")
    private String experienceLevel;       // junior/mid/senior

    private List<String> responsibilities;

    @JsonProperty("key_topics")
    private List<String> keyTopics;

    private Map<String, String> extra;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillItem {
        private String name;
        private String category;    // language/framework/database/cloud/other
        private String importance;  // must/preferred
    }
}
