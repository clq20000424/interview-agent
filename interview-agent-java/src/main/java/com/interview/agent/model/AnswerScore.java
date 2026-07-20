package com.interview.agent.model;

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
public class AnswerScore {

    /**
     * 得分
     */
    private double score;

    /**
     * 反馈
     */
    private String feedback;

    /**
     * 答对的要点列表
     */
    @JsonProperty("key_points_hit")
    private List<String> keyPointsHit;

    /**
     * 答漏的要点列表
     */
    @JsonProperty("key_points_missed")
    private List<String> keyPointsMissed;

    /**
     * 是否应该追问
     */
    @JsonProperty("should_follow_up")
    private boolean shouldFollowUp;
}
