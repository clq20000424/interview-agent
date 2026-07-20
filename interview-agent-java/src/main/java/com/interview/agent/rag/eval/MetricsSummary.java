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

    /**
     * 样本数量
     */
    private int count;

    /**
     * Recall@10
     */
    @JsonProperty("recall_at_10")
    private double recallAt10;

    /**
     * Recall@20
     */
    @JsonProperty("recall_at_20")
    private double recallAt20;

    /**
     * MRR（Mean Reciprocal Rank）
     */
    private double mrr;
}
