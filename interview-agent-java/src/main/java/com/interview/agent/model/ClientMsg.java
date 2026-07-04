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
    // chat / start_interview / answer / upload_questions / quit_interview
    private String type;
    private String content;
    private String jd;
    private String resume;
    private String filename;
    // base64 编码的文件内容
    private String data;
}
