package com.interview.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RRFusionTest {

    /** 验证同时被向量和关键词召回的文档通过 RRF 累积分数排到首位。 */
    @Test
    void shouldPromoteDocumentFoundByBothRoutes() {
        RRFusion fusion = new RRFusion();

        List<RagDocument> result = fusion.fuse(List.of(
                List.of(doc("a"), doc("common")),
                List.of(doc("common"), doc("b"))
        ), 10);

        assertEquals(List.of("common", "a", "b"),
                result.stream().map(RagDocument::getId).toList());
        double expected = 1.0 / (RRFusion.RRF_CONSTANT + 2)
                + 1.0 / (RRFusion.RRF_CONSTANT + 1);
        assertEquals(expected,
                ((Number) result.getFirst().getMetadata().get("_rrf_score")).doubleValue(),
                1e-12);
    }

    /** 验证融合结果保留首个召回源的完整字段，并合并两路检索元数据。 */
    @Test
    void shouldPreserveCanonicalDocumentAndMergeMetadata() {
        RagDocument vectorDoc = RagDocument.builder()
                .id("common")
                .content("向量库中的完整内容")
                .metadata(new HashMap<>(Map.of("_vector_score", 0.91)))
                .userId("user-1")
                .sourceFile("questions.md")
                .score(0.91f)
                .build();
        RagDocument bm25Doc = RagDocument.builder()
                .id("common")
                .content("BM25 内容")
                .metadata(new HashMap<>(Map.of("_bm25_score", 3.2)))
                .build();

        RagDocument result = new RRFusion().fuse(
                List.of(List.of(vectorDoc), List.of(bm25Doc)), 10).getFirst();

        assertEquals("向量库中的完整内容", result.getContent());
        assertEquals("user-1", result.getUserId());
        assertEquals("questions.md", result.getSourceFile());
        assertEquals(0.91f, result.getScore());
        assertEquals(0.91, result.getMetadata().get("_vector_score"));
        assertEquals(3.2, result.getMetadata().get("_bm25_score"));
        assertTrue(result.getMetadata().containsKey("_rrf_score"));
    }

    /** 验证单路内部的重复文档不会被重复计分，并且最终结果遵守 TopK。 */
    @Test
    void shouldIgnoreDuplicatesWithinRouteAndLimitTopK() {
        List<RagDocument> result = new RRFusion().fuse(List.of(
                List.of(doc("a"), doc("a"), doc("c")),
                List.of(doc("b"))
        ), 2);

        assertEquals(List.of("a", "b"), result.stream().map(RagDocument::getId).toList());
        assertEquals(1.0 / (RRFusion.RRF_CONSTANT + 1),
                ((Number) result.getFirst().getMetadata().get("_rrf_score")).doubleValue(),
                1e-12);
    }

    /** 创建带稳定 ID 的最小测试文档。 */
    private static RagDocument doc(String id) {
        return RagDocument.builder()
                .id(id)
                .content("content-" + id)
                .metadata(new HashMap<>())
                .build();
    }
}
