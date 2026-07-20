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
public class QuestionDirection {

    /**
     * 主题
     */
    private String topic;

    /**
     * 问题类型（basic/experience/design）
     */
    private String type;        // basic/experience/design

    /**
     * 难度（easy/medium/hard）
     */
    private String difficulty;  // easy/medium/hard

    /**
     * 搜索查询
     */
    @JsonProperty("search_query")
    private String searchQuery;

    /**
     * 技能列表
     */
    private List<String> skills;

    /**
     * 简历中相关上下文（experience 类必填）
     */
    private String context;     // 简历中相关上下文（experience 类必填）
}
