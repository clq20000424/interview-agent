package com.interview.agent.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天智能体
 *
 * @author 陈龙强
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAgent {

    private final ChatModel chatModel;

    /**
     * 聊天历史上限：20 条消息（10 轮对话）。
     */
    private static final int MAX_HISTORY_SIZE = 20;

    private static final String CHAT_AGENT_PROMPT = """
            你是 InterviewAgent 系统的智能助手，一个专注于技术面试的 AI 伙伴。
            
            你的能力范围：
            1. 回答技术面试相关的问题（面试技巧、知识点讲解、简历建议等）
            2. 帮助用户了解本系统的功能（模拟面试、评估报告、复习计划等）
            3. 日常技术问题的闲聊和答疑
            
            你的行为规范：
            - 友善专业，回答简洁有深度
            - 不要主动替用户做决定，保持引导式对话
            
            【重要：面试引导规则】
            当用户表达出想要面试的意图时（比如"开始面试"、"模拟面试"、"我想练习面试"、"怎么开始"、"怎么用"等），你必须引导用户点击页面底部的「开始面试」按钮。回复示例：
            
            "请点击页面底部的 **「开始面试」** 按钮来启动标准面试流程。点击后你可以：
            - 上传或粘贴 **岗位 JD**（支持链接、文件、文本）
            - 上传或粘贴你的 **简历**
            
            系统会自动完成 JD 分析、简历匹配度评估、智能出题、实时评分，最后生成完整的评估报告和个性化复习计划。"
            
            不要在聊天中直接启动面试流程，因为只有通过按钮才能进入包含 JD 分析、简历匹配、RAG 出题等完整环节的标准化面试。
            
            当前对话上下文中可能包含用户之前面试的历史信息，可以据此提供更个性化的建议。""";

    /**
     * 聊天：维护历史上下文，限制最近 20 条消息
     */
    public String chat(List<Message> history, String userInput) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(CHAT_AGENT_PROMPT));

        // 截取最近 20 条历史消息
        if (history != null && !history.isEmpty()) {
            int startIdx = Math.max(0, history.size() - MAX_HISTORY_SIZE);
            messages.addAll(history.subList(startIdx, history.size()));
        }

        messages.add(new UserMessage(userInput));

        ChatResponse response = chatModel.call(new Prompt(messages));
        return response.getResult().getOutput().getText();
    }
}
