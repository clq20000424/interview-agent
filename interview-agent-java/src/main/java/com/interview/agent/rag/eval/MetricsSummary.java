package com.interview.agent.rag.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指标汇总（用于整体 / 按 topic / 按 difficulty 分组）。
 *
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricsSummary {

    private int count;

    @JsonProperty("recall_at_10")
    private double recallAt10;

    @JsonProperty("recall_at_20")
    private double recallAt20;

    private double mrr;
}
