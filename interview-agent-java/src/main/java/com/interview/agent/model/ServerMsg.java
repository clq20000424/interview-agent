package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * WebSocket 服务端消息。
 * 使用 NON_NULL 策略：空字段不序列化。
 *
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerMsg {
    /**
     * 消息类型（chat_reply/stage_change/question/review_item/memory_weak_points/question_directions/question_plan_details/score）
     */
    private String type;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 当前阶段
     */
    private String stage;

    /**
     * 消息文本
     */
    private String message;

    /**
     * 当前题目编号
     */
    @JsonProperty("question_num")
    private Integer questionNum;

    /**
     * 得分
     */
    private Double score;

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
}
