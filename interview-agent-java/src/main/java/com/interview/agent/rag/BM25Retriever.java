package com.interview.agent.rag;

import lombok.Getter;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25 关键词检索器
 * - k1 = 1.5（控制词频饱和度）
 * - b = 0.75（控制文档长度归一化）
 *
 * @author 陈龙强
 */
public class BM25Retriever {

    @Getter
    private List<RagDocument> documents = new ArrayList<>();
    private Map<String, List<DocTF>> index = new HashMap<>(); // 倒排索引
    private int[] docLen;
    private double avgDL;
    private final int topK;
    private final double k1 = 1.5;
    private final double b = 0.75;

    private static class DocTF {
        int docIdx;
        int tf;

        DocTF(int docIdx, int tf) {
            this.docIdx = docIdx;
            this.tf = tf;
        }
    }

    public BM25Retriever(int topK) {
        this.topK = topK > 0 ? topK : 10;
    }

    /**
     * 构建 BM25 倒排索引
     */
    public void indexDocuments(List<RagDocument> docs) {
        this.documents = new ArrayList<>(docs);
        this.index = new HashMap<>();
        this.docLen = new int[docs.size()];

        int totalLen = 0;
        for (int i = 0; i < docs.size(); i++) {
            List<String> tokens = tokenize(docs.get(i).getContent());
            docLen[i] = tokens.size();
            totalLen += tokens.size();

            Map<String, Integer> tfMap = tokens.stream().collect(Collectors.toMap(t -> t, t -> 1, Integer::sum));
            for (Map.Entry<String, Integer> entry : tfMap.entrySet()) {
                index.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(new DocTF(i, entry.getValue()));
            }
        }

        avgDL = docs.isEmpty() ? 0 : (double) totalLen / docs.size();
    }

    /**
     * BM25 检索
     */
    public List<RagDocument> retrieve(String query) {
        if (documents.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTokens = tokenize(query);
        int n = documents.size();
        double[] scores = new double[n];

        for (String term : queryTokens) {
            List<DocTF> postings = index.get(term);
            if (postings == null) continue;

            // IDF = log((N - df + 0.5) / (df + 0.5) + 1)
            double df = postings.size();
            double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1);

            for (DocTF p : postings) {
                double tf = p.tf;
                double dl = docLen[p.docIdx];
                // BM25 score = IDF * (tf * (k1+1)) / (tf + k1 * (1 - b + b * dl/avgDL))
                double score = idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgDL));
                scores[p.docIdx] += score;
            }
        }

        // 按分数降序排列
        List<int[]> ranked = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (scores[i] > 0) {
                ranked.add(new int[]{i});
            }
        }
        ranked.sort((a, bb) -> Double.compare(scores[bb[0]], scores[a[0]]));

        int limit = Math.min(topK, ranked.size());
        List<RagDocument> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int idx = ranked.get(i)[0];
            RagDocument doc = documents.get(idx);
            RagDocument copy = RagDocument.builder()
                    .id(doc.getId())
                    .content(doc.getContent())
                    .metadata(doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>())
                    .userId(doc.getUserId())
                    .sourceFile(doc.getSourceFile())
                    .build();
            copy.getMetadata().put("_bm25_score", scores[idx]);
            results.add(copy);
        }

        return results;
    }

    /**
     * 追加文档并重建索引
     */
    public void appendDocuments(List<RagDocument> newDocs) {
        documents.addAll(newDocs);
        indexDocuments(documents);
    }

    /**
     * 使用 IK 智能模式切分中英文文本，并把英文统一为小写以保证索引和查询使用相同词项。
     * 技术领域扩展词由 classpath 下的 IKAnalyzer.cfg.xml 和 ik_ext.dic 提供。
     *
     * @param text 待分词的文档或查询文本，可以为空
     * @return 按原文顺序输出的非空词项
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        try (StringReader reader = new StringReader(text)) {
            IKSegmenter segmenter = new IKSegmenter(reader, true);
            Lexeme lexeme;
            while ((lexeme = segmenter.next()) != null) {
                String token = lexeme.getLexemeText().trim().toLowerCase(Locale.ROOT);
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
            return tokens;
        } catch (IOException e) {
            throw new IllegalStateException("IK 分词失败", e);
        }
    }
}
