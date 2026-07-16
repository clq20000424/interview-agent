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
public class QuestionPlan {
    /**
     * 总题目数
     */
    @JsonProperty("total_questions")
    private int totalQuestions;

    /**
     * 题目分布
     */
    private QuestionDistrib distribution;

    /**
     * 题目列表
     */
    private List<PlannedQuestion> questions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionDistrib {
        /**
         * 基础题目数
         */
        private int basic;

        /**
         * 经历题目数
         */
        private int experience;

        /**
         * 设计题目数
         */
        private int design;
    }
}
