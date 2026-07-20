package com.interview.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BM25RetrieverTest {

    /** 验证 IK 能切分无空格中文技术文本，并加载项目扩展词典。 */
    @Test
    void tokenizesChineseTechnicalTerms() {
        List<String> tokens = BM25Retriever.tokenize("Redisson如何实现Redis分布式锁");

        assertTrue(tokens.contains("redis"));
        assertTrue(tokens.contains("分布式锁"));
    }

    /** 验证英文技术词统一转为小写，避免文档与查询大小写不同导致漏召回。 */
    @Test
    void normalizesEnglishTermsToLowerCase() {
        List<String> tokens = BM25Retriever.tokenize("SpringBoot Redis AQS");

        assertTrue(tokens.containsAll(List.of("springboot", "redis", "aqs")));
    }

    /** 验证中文自然语言查询可以召回包含对应技术词的文档。 */
    @Test
    void retrievesChineseDocumentWithoutManualSpaces() {
        BM25Retriever retriever = new BM25Retriever(3);
        RagDocument redis = RagDocument.builder().id("redis").content("Redisson基于Lua实现Redis分布式锁").build();
        RagDocument mysql = RagDocument.builder().id("mysql").content("MySQL使用B+树建立索引").build();
        retriever.indexDocuments(List.of(redis, mysql));

        List<RagDocument> results = retriever.retrieve("Redis分布式锁如何实现");

        assertEquals("redis", results.getFirst().getId());
        assertTrue((double) results.getFirst().getMetadata().get("_bm25_score") > 0);
    }

    /** 验证空输入不会调用 IK 或产生无意义词项。 */
    @Test
    void returnsEmptyTokensForBlankInput() {
        assertTrue(BM25Retriever.tokenize(null).isEmpty());
        assertTrue(BM25Retriever.tokenize("  ").isEmpty());
    }
}
