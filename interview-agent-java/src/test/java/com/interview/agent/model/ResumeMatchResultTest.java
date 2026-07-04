package com.interview.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author 陈龙强
 */
class ResumeMatchResultTest {

    @Test
    void shouldIgnoreUnknownSkillMatchFields() throws Exception {
        String json = """
                {
                  "overall_score": 80.0,
                  "skill_match": [
                    {
                      "skill_name": "Java",
                      "required": true,
                      "preferred": false,
                      "matched": true,
                      "match_score": 90.0,
                      "evidence": "项目中使用 Java"
                    }
                  ],
                  "unexpected": "ignored"
                }
                """;

        ResumeMatchResult result = new ObjectMapper().readValue(json, ResumeMatchResult.class);

        assertThat(result.getOverallScore()).isEqualTo(80.0);
        assertThat(result.getSkillMatch()).hasSize(1);
        assertThat(result.getSkillMatch().get(0).getSkillName()).isEqualTo("Java");
        assertThat(result.getSkillMatch().get(0).isMatched()).isTrue();
    }
}
