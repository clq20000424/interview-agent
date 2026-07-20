package com.interview.agent.model;

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
public class QuestionDirectionPlan {
    /**
     * 考查方向列表
     */
    private List<QuestionDirection> directions;
}
