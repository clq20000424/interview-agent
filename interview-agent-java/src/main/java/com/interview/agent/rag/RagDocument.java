package com.interview.agent.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 文档（统一的文档表示）
 *
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocument {
    /**
     * 文档唯一 ID
     */
    private String id;

    /**
     * 文档内容
     */
    private String content;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 来源文件
     */
    private String sourceFile;

    /**
     * 检索得分
     */
    private float score;
}
