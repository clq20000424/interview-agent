package com.interview.agent.graph;

import com.interview.agent.model.AnswerScore;

/**
 * 面试过程回调
 *
 * @author 陈龙强
 */
public interface InterviewCallbacks {

    /**
     * 阶段变化回调
     */
    void onStageChange(String stage, String msg);

    /**
     * 题目回调
     */
    void onQuestion(int questionNum, String content);

    /**
     * 评分回调
     */
    void onScore(AnswerScore score);

    /**
     * 报告回调
     */
    void onReport(String report);

    /**
     * 复习计划回调
     */
    void onReviewPlan(String plan);

    /**
     * 简历匹配结果回调
     */
    void onResumeMatch(com.interview.agent.model.ResumeMatchResult matchResult);

    /**
     * 获取用户回答（阻塞等待）
     */
    String getUserAnswer() throws InterruptedException, UserQuitException;
}
