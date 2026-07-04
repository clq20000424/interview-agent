package com.interview.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.model.JDAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author 陈龙强
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JDAnalyzer {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String JD_ANALYZER_PROMPT = """
            你是一个专业的 JD（职位描述）分析专家。请仔细分析以下职位描述，提取关键信息。

            请按照以下 JSON 格式输出分析结果（不要输出其他内容，只输出纯 JSON）：

            {
              "position": "岗位名称",
              "company": "公司名称（如果JD中有提及）",
              "required_skills": [
                {"name": "技能名称", "category": "language/framework/database/cloud/other", "importance": "must"}
              ],
              "preferred_skills": [
                {"name": "技能名称", "category": "language/framework/database/cloud/other", "importance": "preferred"}
              ],
              "experience_level": "junior/mid/senior",
              "responsibilities": ["职责1", "职责2"],
              "key_topics": ["面试重点方向1", "面试重点方向2"]
            }

            注意：
            1. required_skills 是 JD 中明确要求的必须技能
            2. preferred_skills 是"加分项"或"优先考虑"的技能
            3. experience_level 根据工作年限和岗位级别判断
            4. key_topics 是基于 JD 推断出的面试重点考察方向""";

    public JDAnalysis analyze(String jdText) {
        log.info("[JDAnalyzer] 开始分析 JD，长度: {}", jdText.length());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(JD_ANALYZER_PROMPT),
                new UserMessage("请分析以下 JD：\n\n" + jdText)
        ));

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText();
        String json = AgentUtils.extractJSON(content);

        try {
            JDAnalysis analysis = objectMapper.readValue(json, JDAnalysis.class);
            analysis.setRawJD(jdText);
            log.info("[JDAnalyzer] 分析完成: {} - {}", analysis.getPosition(), analysis.getExperienceLevel());
            return analysis;
        } catch (Exception e) {
            throw new RuntimeException("JD 分析结果解析失败: " + e.getMessage(), e);
        }
    }
}
