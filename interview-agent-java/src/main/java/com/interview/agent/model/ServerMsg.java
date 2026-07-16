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
    // chat_reply/stage_change/question/review_item/score/report/review_plan/error/upload_result/interview_complete
    private String type;
    private String content;
    private String stage;
    private String message;

    @JsonProperty("question_num")
    private Integer questionNum;

    private Double score;
    private String feedback;

    @JsonProperty("key_points_hit")
    private List<String> keyPointsHit;

    @JsonProperty("key_points_missed")
    private List<String> keyPointsMissed;
}
