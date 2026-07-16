package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    private String role;
    private String content;

    @JsonProperty("message_type")
    private String messageType;

    /**
     * 展示消息所需的结构化元数据，例如 stage、question_num、score 和评分要点。
     * 使用通用 Map 可以在新增消息组件时保持数据库 JSON 向后兼容。
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
