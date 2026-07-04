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
public class PlannedQuestion {

    /**
     * id
     */
    private String id;

    /**
     * 内容
     */
    private String content;

    /**
     * 问题类型 basic/experience/design
     */
    private String type;

    /**
     * 难易程度 easy/medium/hard
     */
    private String difficulty;

    /**
     * skill 列表
     */
    private List<String> skills;

    /**
     * 追问信息
     */
    @JsonProperty("follow_ups")
    private List<String> followUps;

    /**
     * 引用信息
     */
    private String reference;

    /**
     * 题库原题ID 或 "llm"
     */
    private String source;
}
