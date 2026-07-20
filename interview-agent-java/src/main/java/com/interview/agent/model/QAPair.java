package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QAPair {

    /**
     * 问题
     */
    private PlannedQuestion question;

    /**
     * 用户答案
     */
    @JsonProperty("user_answer")
    private String userAnswer;

    /**
     * 得分
     */
    private double score;

    /**
     * 反馈
     */
    private String feedback;

    /**
     * 是否追问
     */
    @JsonProperty("follow_up_used")
    private boolean followUpUsed;
}
