package com.interview.agent.rag;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RRF（Reciprocal Rank Fusion）融合算法。
 * 对多路有序召回结果按排名倒数累加，统一完成跨路去重和排序，k = 60。
 *
 * @author 陈龙强
 */
@Component
public class RRFusion {

    /**
     * RRF 融合算法的常数 k，默认 60
     */
    public static final int RRF_CONSTANT = 60;

    /**
     * 多路召回 RRF 融合
     *
     * @param allResults 每一路的检索结果
     * @param topK       最终返回的文档数
     */
    public List<RagDocument> fuse(List<List<RagDocument>> allResults, int topK) {
        if (allResults == null || allResults.isEmpty()) {
            return List.of();
        }
        if (topK <= 0) {
            topK = 10;
        }

        // docID -> 融合分数
        Map<String, Double> scoreMap = new HashMap<>();
        // docID -> 首次召回的文档，优先保留 Milvus 等排在前面的召回源中的完整字段。
        Map<String, RagDocument> docMap = new HashMap<>();
        // 合并不同召回源写入的向量分数、BM25 分数等元数据。
        Map<String, Map<String, Object>> metadataMap = new HashMap<>();
        // 并列分数按首次出现顺序稳定排序，避免 HashMap 遍历顺序导致结果漂移。
        Map<String, Integer> firstSeenOrder = new HashMap<>();

        for (List<RagDocument> results : allResults) {
            if (results == null || results.isEmpty()) {
                continue;
            }
            Set<String> seenInRoute = new HashSet<>();
            for (int rank = 0; rank < results.size(); rank++) {
                RagDocument doc = results.get(rank);
                if (doc == null) {
                    continue;
                }
                String id = docID(doc);
                // 单路结果异常重复时只按第一次出现的排名计分，避免重复抬高融合分。
                if (!seenInRoute.add(id)) {
                    continue;
                }
                // RRF score = sum(1 / (k + rank + 1))
                scoreMap.merge(id, 1.0 / (RRF_CONSTANT + rank + 1), Double::sum);
                docMap.putIfAbsent(id, doc);
                firstSeenOrder.putIfAbsent(id, firstSeenOrder.size());
                if (doc.getMetadata() != null) {
                    metadataMap.computeIfAbsent(id, ignored -> new HashMap<>())
                            .putAll(doc.getMetadata());
                }
            }
        }

        // 按 RRF 分数降序排列
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scoreMap.entrySet());
        ranked.sort((a, b) -> {
            int scoreComparison = Double.compare(b.getValue(), a.getValue());
            if (scoreComparison != 0) {
                return scoreComparison;
            }
            return Integer.compare(firstSeenOrder.get(a.getKey()), firstSeenOrder.get(b.getKey()));
        });

        int limit = Math.min(topK, ranked.size());
        List<RagDocument> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Double> entry = ranked.get(i);
            RagDocument doc = docMap.get(entry.getKey());
            Map<String, Object> metadata = new HashMap<>(metadataMap.getOrDefault(entry.getKey(), Map.of()));
            metadata.put("_rrf_score", entry.getValue());
            RagDocument copy = RagDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .metadata(metadata)
                    .userId(doc.getUserId())
                    .sourceFile(doc.getSourceFile())
                    .score(doc.getScore())
                    .build();
            results.add(copy);
        }

        return results;
    }

    /**
     * 获取跨召回源稳定的文档标识；缺少 ID 时使用内容前缀兼容旧数据。
     */
    private String docID(RagDocument doc) {
        if (doc.getId() != null && !doc.getId().isEmpty()) {
            return doc.getId();
        }
        String content = doc.getContent();
        if (content != null && content.length() > 100) {
            return content.substring(0, 100);
        }
        return content != null ? content : "";
    }
}
