package com.interview.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 客户端消息。
 *
 * @author 陈龙强
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientMsg {
    /**
     * 类型（chat、start_interview、answer、upload_questions、quit_interview、new_chat、load_session）
     */
    private String type;

    /**
     * 内容
     */
    private String content;

    /**
     * 会话 id
     */
    private String sessionId;

    /**
     * 岗位描述
     */
    private String jd;

    /**
     * 简历
     */
    private String resume;

    /**
     * 文件名
     */
    private String filename;

    /**
     * base64 编码的文件内容
     */
    private String data;
}
