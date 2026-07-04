package com.interview.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.model.*;
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
public class QuestionPlanner {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============================================================
    // Phase 1：规划出题方向
    // ============================================================
    private static final String DIRECTION_PLANNER_PROMPT = """
            你是一个资深的技术面试出题规划专家。根据 JD 分析和简历匹配结果，规划面试的出题方向。
            你的任务是：为每道题确定一个考察方向/考点，而不是出具体的题目。

            ====================【最重要：数量硬性约束，每个类型都要按难度分档铺满】====================
            你输出的 directions 数组中，按 type + difficulty 统计的数量必须严格满足：
              · type = "basic"      ：easy 5 个、medium 5 个、hard 5 个，共 15 个
              · type = "experience" ：easy 4 个、medium 4 个、hard 4 个，共 12 个
              · type = "design"     ：medium 2 个、hard 2 个，共 4 个
            因此 directions 总数应为 31 个。

            ⚠️ 为什么每档都必须铺满：以上是面试用的【候选题池】，面试时会按候选人的实时表现
            自适应抽取对应难度的题目（答得好升档、答得差降档），并不要求把候选题全部问完。
            因此每个难度档都必须铺满足够的候选方向——同一难度若只有一两个候选，连续答对/答错时
            就会无题可抽、难度调节形同虚设。所以宁可多铺，也不能让某个档位缺题。
            另外，basic（基础知识）方向是面试【题库原题】的唯一来源——每个 basic 方向都会去候选人
            题库里检索一道匹配的原题，basic 铺满 15 个也能让命中的题库原题更多、出题质量更高。
            如果候选人简历直接提到的知识点不足以铺满，就结合 JD 要求里的核心技术栈、以及该岗位
            常见的各档基础/经验/设计考点继续补充独立方向，直到每个档位的配额都达标为止。
            ===================================================================================

            题型说明（出题方向以候选人简历的技术栈和项目经历为主，JD 要求为辅）：
            - basic：核心技术知识点（语言特性、框架原理、中间件、数据库、并发、网络、操作系统等），
              每个知识点拆成一个独立方向；优先覆盖简历与 JD 共同涉及的技术栈
            - experience：针对简历中的工作 / 实习 / 项目经历的考察方向（必须基于简历真实内容）
            - design：系统设计、架构设计类方向，结合简历项目背景

            其他要求：
            1. 每个方向给出一个用于题库检索的关键词（search_query），要简洁精准（如"MySQL索引优化"、"Go channel原理"）
            2. experience 类方向必须基于简历中的真实信息，context 字段填写简历中的相关内容摘要
            3. 每个方向的 difficulty 必须标注准确，且严格符合上面按难度分档的数量配额（同一 type 下 easy/medium/hard 的方向数量必须达标）
            4. 【严禁幻觉】experience 类必须严格基于简历中的真实信息，不得杜撰或假设简历中未提及的技术细节

            请按以下 JSON 格式输出（不要输出其他任何内容）。
            ‼️ 输出前请自检一遍：basic 是否 easy/medium/hard 各 5 个？experience 是否各 4 个？design 是否 medium/hard 各 2 个？如不满足，必须调整后再输出。

            {
              "directions": [
                {
                  "topic": "考察方向描述（如：Go sync.Map 的并发安全机制）",
                  "type": "basic/experience/design",
                  "difficulty": "easy/medium/hard",
                  "search_query": "题库检索关键词（如：sync.Map 并发）",
                  "skills": ["考察的技能点"],
                  "context": "简历中相关上下文（experience 类必填，其他类型可为空）"
                }
              ]
            }""";

    // ============================================================
    // Phase 2：组装最终题目
    // ============================================================
    private static final String QUESTION_ASSEMBLER_PROMPT = """
            你是一个资深的技术面试出题专家。根据出题方向和题库匹配结果，生成最终的面试题目。

            规则：
            1. 【数量严格对应，最重要的规则】每个出题方向必须对应生成恰好一道题目，不得合并、删减或跳过任何方向。输入 N 个方向就必须输出 N 道题
            2. 如果提供了题库匹配的原题，直接使用原题（content 完全照搬不得改编），source 填题目 ID
            3. 如果没有匹配到题库原题，由你根据出题方向自行出题，source 填 "llm"
            4. 【LLM 出题基于简历】当 LLM 自行出题时，必须结合候选人简历的技术栈和项目经历来出题，确保题目与候选人背景相关
            5. 【严禁幻觉】experience 类题目必须严格基于简历中的真实信息提问，不得杜撰
            6. 题目 content 必须简洁精炼，一句话直击考察要点
            7. 每道题准备 1-2 个追问，用于深入考察
            8. 【难度沿用】每道题的 difficulty 必须与其对应出题方向给定的 difficulty 完全一致，不得更改，以保持整体难度分布的梯度

            请按以下 JSON 格式输出（不要输出其他内容）：

            {
              "total_questions": 10,
              "distribution": {
                "basic": 0,
                "experience": 0,
                "design": 0
              },
              "questions": [
                {
                  "id": "q1",
                  "content": "题目内容",
                  "type": "basic/experience/design",
                  "difficulty": "easy/medium/hard",
                  "skills": ["考察的技能点"],
                  "follow_ups": ["追问1", "追问2"],
                  "reference": "参考答案要点",
                  "source": "题库原题ID 或 llm"
                }
              ]
            }""";

    /**
     * Phase 1：规划出题方向
     */
    public QuestionDirectionPlan planDirections(JDAnalysis jd, ResumeMatchResult match, String weakPoints) {
        log.info("[QuestionPlanner] Phase 1: 规划出题方向");

        StringBuilder userMsg = new StringBuilder();
        try {
            userMsg.append("## JD 分析结果\n");
            userMsg.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jd));
            userMsg.append("\n\n## 简历匹配结果\n");
            userMsg.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(match));
        } catch (Exception e) {
            userMsg.append(jd.toString()).append("\n").append(match.toString());
        }

        if (weakPoints != null && !weakPoints.isEmpty()) {
            userMsg.append("\n\n## 历史薄弱点（需重点考察）\n").append(weakPoints);
        }

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(DIRECTION_PLANNER_PROMPT),
                new UserMessage(userMsg.toString())
        ));

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText();
        String json = AgentUtils.extractJSON(content);

        try {
            QuestionDirectionPlan plan = objectMapper.readValue(json, QuestionDirectionPlan.class);
            log.info("[QuestionPlanner] Phase 1 完成，规划了 {} 个出题方向", plan.getDirections().size());
            return plan;
        } catch (Exception e) {
            throw new RuntimeException("出题方向解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * Phase 2：按方向组装最终题目
     */
    public QuestionPlan assembleQuestions(JDAnalysis jd, ResumeMatchResult match,
                                          QuestionDirectionPlan directions, List<String> directionDocs) {
        log.info("[QuestionPlanner] Phase 2: 组装题目，{} 个方向", directions.getDirections().size());

        StringBuilder userMsg = new StringBuilder();
        userMsg.append("## 出题方向\n");
        List<QuestionDirection> dirs = directions.getDirections();
        for (int i = 0; i < dirs.size(); i++) {
            QuestionDirection dir = dirs.get(i);
            userMsg.append(String.format("\n### 方向 %d\n", i + 1));
            userMsg.append(String.format("- 考点: %s\n- 类型: %s\n- 难度: %s\n", dir.getTopic(), dir.getType(), dir.getDifficulty()));

            String doc = (directionDocs != null && i < directionDocs.size()) ? directionDocs.get(i) : "";
            if (doc != null && !doc.isEmpty()) {
                userMsg.append(String.format("- 题库匹配:\n%s\n", doc));
            } else {
                userMsg.append("- 题库匹配: 无匹配，请自行出题\n");
            }
        }

        try {
            userMsg.append("\n## JD 分析（出题参考）\n");
            userMsg.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jd));
            userMsg.append("\n\n## 简历匹配（出题参考）\n");
            userMsg.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(match));
        } catch (Exception ignored) {}

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(QUESTION_ASSEMBLER_PROMPT),
                new UserMessage(userMsg.toString())
        ));

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText();
        String json = AgentUtils.extractJSON(content);

        try {
            QuestionPlan plan = objectMapper.readValue(json, QuestionPlan.class);
            log.info("[QuestionPlanner] Phase 2 完成，共 {} 道题", plan.getQuestions().size());
            return plan;
        } catch (Exception e) {
            throw new RuntimeException("题目组装解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 动态难度调节算法（与 Go 版本完全一致）
     * - 连续答对 ≥ 2 题 → 提高难度
     * - 连续答错 ≥ 2 题 → 降低难度
     * - 否则保持当前难度
     */
    public String adjustDifficulty(InterviewState state) {
        if (state.getConsecutiveRight() >= 2) {
            return switch (state.getCurrentDifficulty()) {
                case "easy" -> "medium";
                case "medium" -> "hard";
                default -> "hard";
            };
        }

        if (state.getConsecutiveWrong() >= 2) {
            return switch (state.getCurrentDifficulty()) {
                case "hard" -> "medium";
                case "medium" -> "easy";
                default -> "easy";
            };
        }

        return state.getCurrentDifficulty();
    }
}
