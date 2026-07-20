package com.interview.agent.rag.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一次评估运行的完整报告。
 *
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvalReport {

    /**
     * 数据集版本号
     */
    @JsonProperty("dataset_version")
    private String datasetVersion;

    /**
     * 数据集路径
     */
    @JsonProperty("dataset_path")
    private String datasetPath;

    /**
     * 样本总数
     */
    @JsonProperty("sample_count")
    private int sampleCount;

    /**
     * 运行时间
     */
    @JsonProperty("run_at")
    private LocalDateTime runAt;

    /**
     * 耗时
     */
    private String duration;

    /**
     * RAG 配置快照
     */
    private RagConfigSnapshot config;

    /**
     * 整体指标
     */
    private MetricsSummary overall;

    /**
     * 按 topic 分组的指标
     */
    @JsonProperty("topic_metrics")
    private Map<String, MetricsSummary> topicMetrics;

    /**
     * 按 difficulty 分组的指标
     */
    @JsonProperty("difficulty_metrics")
    private Map<String, MetricsSummary> difficultyMetrics;

    /**
     * 所有样本的评估结果
     */
    @JsonProperty("sample_results")
    private List<SampleResult> sampleResults;

    /**
     * 按 Recall@10 升序的前 10 条
     */
    @JsonProperty("worst_samples")
    private List<SampleResult> worstSamples;
}
