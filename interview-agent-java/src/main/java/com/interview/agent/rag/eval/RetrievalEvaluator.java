package com.interview.agent.rag.eval;

import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.MilvusStore;
import com.interview.agent.rag.RagDocument;
import com.interview.agent.rag.RRFusion;
import com.interview.agent.rag.Reranker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 离线检索评估流水线。
 * <p>
 * 整体流程：
 * 1. 遍历每条样本，用 sample.query 跑完整的 RAG 检索（Milvus + BM25 + RRF + Rerank）
 * 2. 对比检索结果和 sample.relevantDocIds，计算 Recall@10 / Recall@20 / MRR
 * 3. 汇总成 EvalReport，附带按 topic / difficulty 分组指标与异常样本列表
 *
 * <p>retrieveForEval 必须和 Orchestrator 的 RAG 检索阶段保持一致：
 * Milvus + BM25 双路召回 → RRF 融合 → Reranker。
 *
 * @author 陈龙强
 */
@Slf4j
@Component
public class RetrievalEvaluator {

    /**
     * 评估时 Milvus 召回、RRF 融合和 Rerank 的 TopK（默认 20；可用环境变量调整做 A/B 对比）。
     */
    public static final int RETRIEVE_TOP_K =
            Integer.parseInt(System.getenv().getOrDefault("EVAL_RETRIEVE_TOP_K", "20"));

    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;
    private final RRFusion rrfFusion;
    private final Reranker reranker;

    public RetrievalEvaluator(MilvusStore milvusStore, BM25Manager bm25Manager,
                              RRFusion rrfFusion, Reranker reranker) {
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
    }

    /**
     * 当前生效的 rerank 类型（llm / cross-encoder / none），供评估报告如实记录
     */
    public String activeRerankerType() {
        return reranker != null ? reranker.activeType() : "none";
    }

    /**
     * 对评估数据集做全量检索评估，返回完整报告。
     *
     * @param dataset     评估样本集
     * @param userId      题库按 user 隔离，评估用哪个用户的题库（eval_user）
     * @param ragCfg      RAG 配置快照（写入报告，便于 A/B 对比）
     * @param useReranker 是否启用模型 Rerank（false 用于 A/B 对比）
     */
    public EvalReport runEvaluation(List<EvalSample> dataset, String userId,
                                    RagConfigSnapshot ragCfg, boolean useReranker) {
        if (dataset == null || dataset.isEmpty()) {
            throw new IllegalArgumentException("eval: dataset is empty");
        }
        long start = System.currentTimeMillis();
        List<SampleResult> results = new ArrayList<>(dataset.size());

        for (int i = 0; i < dataset.size(); i++) {
            EvalSample sample = dataset.get(i);
            log.info("[Eval] ({}/{}) sample={} query=\"{}\"", i + 1, dataset.size(), sample.getId(), sample.getQuery());

            List<RagDocument> retrievedDocs;
            try {
                retrievedDocs = retrieveForEval(userId, sample.getQuery(), useReranker);
            } catch (Exception e) {
                log.warn("[Eval] sample {} retrieve failed: {}", sample.getId(), e.getMessage());
                continue;
            }
            List<String> retrievedIds = docsToIds(retrievedDocs);

            double recall10 = EvalMetrics.calcRecallAtK(retrievedIds, sample.getRelevantDocIds(), 10);
            double recall20 = EvalMetrics.calcRecallAtK(retrievedIds, sample.getRelevantDocIds(), 20);
            double mrr = EvalMetrics.calcMRR(retrievedIds, sample.getRelevantDocIds());
            EvalMetrics.HitResult hit = EvalMetrics.calcHits(retrievedIds, sample.getRelevantDocIds());

            results.add(SampleResult.builder()
                    .sampleId(sample.getId())
                    .query(sample.getQuery())
                    .topic(sample.getTopic())
                    .difficulty(sample.getDifficulty())
                    .retrievedIds(retrievedIds)
                    .relevantIds(sample.getRelevantDocIds())
                    .hitIds(hit.hits())
                    .recallAt10(recall10)
                    .recallAt20(recall20)
                    .mrr(mrr)
                    .firstHitRank(hit.firstHitRank())
                    .build());
        }

        EvalReport report = EvalMetrics.aggregateReport(results, ragCfg);
        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        report.setDuration(String.format("%.2fs", seconds));
        return report;
    }

    /**
     * 评估时的检索流程：Milvus + BM25 双路召回 → RRF 融合 → 可选 Reranker。
     * 即使跳过 Reranker 也保留 RRF，确保 A/B 实验只改变精排变量。
     */
    private List<RagDocument> retrieveForEval(String userId, String query, boolean useReranker) {
        List<RagDocument> milvusResults = List.of();
        List<RagDocument> bm25Results = List.of();

        if (milvusStore != null) {
            try {
                milvusResults = milvusStore.retrieveByUser(userId, query, RETRIEVE_TOP_K);
            } catch (Exception e) {
                log.warn("[Eval] Milvus retrieve err: {}", e.getMessage());
            }
        }
        if (bm25Manager != null) {
            try {
                bm25Results = bm25Manager.retrieve(userId, query);
            } catch (Exception e) {
                log.warn("[Eval] BM25 retrieve err: {}", e.getMessage());
            }
        }
        if (milvusResults == null) {
            milvusResults = List.of();
        }
        if (bm25Results == null) {
            bm25Results = List.of();
        }
        List<RagDocument> docs = rrfFusion.fuse(
                List.of(milvusResults, bm25Results), RETRIEVE_TOP_K);
        log.debug("[Eval] RRF fused: milvus={}, bm25={}, fused={}, rrfK={}",
                milvusResults.size(), bm25Results.size(), docs.size(), RRFusion.RRF_CONSTANT);

        if (useReranker && reranker != null && docs.size() > 1) {
            // rerank 返回 RETRIEVE_TOP_K(20) 条，与 RAGConfig 快照 rerank_top_n 和 Go 报告对齐，
            // 避免 rerank 把 20 条召回截断到默认 10、人为压低 Recall@20
            List<RagDocument> reranked = reranker.rerank(query, docs, RETRIEVE_TOP_K);
            if (reranked != null && !reranked.isEmpty()) {
                docs = reranked;
            }
        }
        return docs;
    }

    private static List<String> docsToIds(List<RagDocument> docs) {
        List<String> ids = new ArrayList<>(docs.size());
        for (RagDocument d : docs) {
            ids.add(d.getId());
        }
        return ids;
    }
}
