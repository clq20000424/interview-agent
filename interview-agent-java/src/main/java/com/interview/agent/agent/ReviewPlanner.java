package com.interview.agent.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.mcp.GitHubTool;
import com.interview.agent.model.EvaluationReport;
import com.interview.agent.model.ReviewPlan;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 复习规划 Agent —— 全项目唯一真正调用外部工具的 Agent，用 Spring AI Alibaba 的 ReactAgent 实现：
 * 模型根据面试评估报告自主决定是否、用什么关键词调用 GitHub 搜索工具来推荐真实开源项目，
 * 再综合产出个性化复习计划（ReactAgent 的 tool-calling 循环，区别于其余单轮 LLM Agent）。
 *
 * @author 陈龙强
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewPlanner {

    private static final int SINGLE_TURN_MAX_ATTEMPTS = 2;

    /**
     * 让编排层能够区分 AI 生成和本地降级，并向用户明确披露。
     */
    public record GenerationResult(ReviewPlan plan, boolean fallback) {
    }

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * GitHub 工具（仅配置了 token 时存在）；存在时作为 ReactAgent 的工具注入
     */
    @Setter
    @Autowired(required = false)
    private GitHubTool gitHubTool;

    // 注意：instruction 会被框架按 f-string 模板渲染（{x} 视为占位符），因此这里不能出现裸 {}。
    // 具体 JSON 输出格式放到用户消息里（用户输入不经模板渲染）。
    private static final String REVIEW_PLANNER_INSTRUCTION = """
            你是一位技术学习路径规划专家，要根据候选人的面试评估报告制定一份个性化的复习计划。
            
            你可以使用 search_github_repos 工具：当候选人存在明显薄弱领域、需要推荐真实可用的开源项目或教程时，
            自行决定用合适的英文技术关键词（通常取 1~3 个高优先级薄弱点）调用它，并把搜到的真实项目写进推荐资源、
            type 设为 repo、url 用搜到的真实链接。也可以补充经典书籍、官方文档等非 GitHub 资源。
            如果工具不可用或没搜到结果，就只用你已知的优质资源，不要编造链接。
            
            规划原则：优先解决高优先级薄弱点；每个学习项给出可执行的具体行动；推荐资源实用、高质量；时间估算合理。
            最终只输出用户要求的那个纯 JSON 对象，不要输出任何工具调用过程、思考说明或多余文字。""";

    // 用户消息里携带报告与 JSON 结构要求（含 {}，但用户输入不被模板渲染，安全）。
    private static final String OUTPUT_FORMAT = """
            
            请严格按以下 JSON 格式输出（只输出 JSON）：
            {
              "weak_areas": [
                {"topic": "薄弱领域名称", "score": 50.0, "priority": "high/medium/low"}
              ],
              "study_plan": [
                {"topic": "学习主题", "objective": "学习目标", "actions": ["具体行动1", "具体行动2"], "time_estimate": "预估时间"}
              ],
              "resources": [
                {"title": "资源标题", "type": "article/video/repo/book", "url": "链接（如有）", "desc": "推荐理由"}
              ]
            }""";

    /**
     * 根据面试评估报告生成复习计划。优先使用带工具的 ReactAgent，失败后重试普通模型，
     * 所有模型路径均失败时返回本地基础计划并标记为降级结果。
     *
     * @param report 本次面试的评估报告
     * @return 复习计划及是否发生本地降级的生成结果
     */
    public GenerationResult plan(EvaluationReport report) {
        log.info("[ReviewPlanner] 开始生成复习计划");

        String reportJson;
        try {
            reportJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            reportJson = report.toString();
        }
        String userMsg = "## 面试评估报告\n" + reportJson + "\n" + OUTPUT_FORMAT;

        ReviewPlan plan = tryParsePlan(generateWithReactAgent(userMsg), report.getSessionId(), "ReactAgent");
        if (plan != null) {
            return new GenerationResult(plan, false);
        }

        // ReactAgent 失败或输出不可解析时，退回无工具的单轮调用，并对瞬时故障有限重试。
        for (int attempt = 1; attempt <= SINGLE_TURN_MAX_ATTEMPTS; attempt++) {
            String content = generateSingleTurn(userMsg, attempt);
            plan = tryParsePlan(content, report.getSessionId(), "普通模型第 " + attempt + " 次调用");
            if (plan != null) {
                return new GenerationResult(plan, false);
            }
        }

        log.error("[ReviewPlanner] 所有模型调用均失败，使用评估报告生成本地基础复习计划");
        return new GenerationResult(buildFallbackPlan(report), true);
    }

    /**
     * 调用不带外部工具的普通聊天模型生成一次复习计划文本。
     *
     * @param userMsg 包含评估报告和输出格式要求的用户消息
     * @param attempt 当前重试次数，仅用于日志定位
     * @return 模型文本；调用异常或响应无效时返回 null
     */
    private String generateSingleTurn(String userMsg, int attempt) {
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(REVIEW_PLANNER_INSTRUCTION),
                    new UserMessage(userMsg)
            )));
            return extractResponseText(response);
        } catch (Exception e) {
            log.warn("[ReviewPlanner] 普通模型第 {} 次调用失败: {}", attempt, e.getMessage());
            return null;
        }
    }

    /**
     * 从普通模型响应中提取非空文本，并统一校验响应对象的完整性。
     *
     * @param response 普通聊天模型响应
     * @return 非空的模型输出文本
     * @throws IllegalStateException 响应、生成结果、输出消息或文本为空时抛出
     */
    private String extractResponseText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("模型未返回有效响应");
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型返回内容为空");
        }
        return content;
    }

    /**
     * 尝试把指定来源的模型文本解析为复习计划，解析失败时记录日志并允许调用方继续降级。
     *
     * @param content   模型输出文本
     * @param sessionId 当前面试 Session ID
     * @param source    输出来源，用于区分 ReactAgent 和普通模型日志
     * @return 解析成功的复习计划；内容为空或解析失败时返回 null
     */
    private ReviewPlan tryParsePlan(String content, String sessionId, String source) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return parsePlan(content, sessionId);
        } catch (RuntimeException e) {
            log.warn("[ReviewPlanner] {} 输出不可解析: {}", source, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 LLM 输出为 ReviewPlan，并对缺失的列表字段做空集合规范化，避免后续 NPE。
     * 包级可见，便于单元测试覆盖「LLM 漏字段」场景。
     */
    ReviewPlan parsePlan(String content, String sessionId) {
        String json = AgentUtils.extractJSON(content);
        try {
            ReviewPlan plan = objectMapper.readValue(json, ReviewPlan.class);
            plan.setSessionId(sessionId);
            plan.setCreatedAt(LocalDateTime.now());
            // 容错：LLM 输出可能缺字段导致列表为 null，规范化为空集合，避免后续 NPE
            if (plan.getWeakAreas() == null) plan.setWeakAreas(new ArrayList<>());
            if (plan.getStudyPlan() == null) plan.setStudyPlan(new ArrayList<>());
            if (plan.getResources() == null) plan.setResources(new ArrayList<>());
            log.info("[ReviewPlanner] 复习计划生成完成，{} 个薄弱领域", plan.getWeakAreas().size());
            return plan;
        } catch (Exception e) {
            throw new RuntimeException("复习计划解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用 ReactAgent（带 GitHub 工具，由模型自主调用）生成复习计划文本。失败返回 null 以便降级。
     */
    private String generateWithReactAgent(String userMsg) {
        try {
            List<ToolCallback> tools = (gitHubTool != null)
                    ? List.of(gitHubTool.asToolCallback())
                    : List.of();

            ReactAgent agent = ReactAgent.builder()
                    .name("review_planner")
                    .model(chatModel)
                    .instruction(REVIEW_PLANNER_INSTRUCTION)
                    .tools(tools)
                    .build();

            AssistantMessage msg = agent.call(userMsg);
            String content = msg != null ? msg.getText() : null;
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("ReactAgent 返回内容为空");
            }
            return content;
        } catch (Exception e) {
            log.warn("[ReviewPlanner] ReactAgent 执行失败，降级为单轮生成: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 模型服务不可用时，根据已有评估数据构造可执行的基础计划，避免面试流程在最后一步中断。
     */
    ReviewPlan buildFallbackPlan(EvaluationReport report) {
        List<ReviewPlan.WeakArea> weakAreas = new ArrayList<>();
        List<String> weaknesses = report.getWeaknesses();
        Map<String, Double> dimensionScores = report.getDimensionScore();

        if (weaknesses != null && !weaknesses.isEmpty()) {
            for (String topic : weaknesses) {
                if (topic == null || topic.isBlank()) {
                    continue;
                }
                double score = findTopicScore(topic, dimensionScores, report.getOverallScore());
                weakAreas.add(createWeakArea(topic, score));
            }
        } else if (dimensionScores != null && !dimensionScores.isEmpty()) {
            dimensionScores.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
                    .sorted(Map.Entry.comparingByValue())
                    .limit(3)
                    .map(entry -> createWeakArea(entry.getKey(), entry.getValue()))
                    .forEach(weakAreas::add);
        }

        List<ReviewPlan.StudyItem> studyPlan = weakAreas.stream()
                .sorted(Comparator.comparingDouble(ReviewPlan.WeakArea::getScore))
                .map(this::createFallbackStudyItem)
                .toList();

        return ReviewPlan.builder()
                .sessionId(report.getSessionId())
                .weakAreas(weakAreas)
                .studyPlan(studyPlan)
                .resources(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * 从维度得分中查找与薄弱主题名称相互包含的得分，找不到时使用总分兜底。
     *
     * @param topic           薄弱主题
     * @param dimensionScores 评估报告中的维度得分
     * @param defaultScore    未匹配到维度时使用的默认分数
     * @return 主题对应的维度分数或默认分数
     */
    private double findTopicScore(String topic, Map<String, Double> dimensionScores, double defaultScore) {
        if (dimensionScores == null || dimensionScores.isEmpty()) {
            return defaultScore;
        }
        String normalizedTopic = topic.toLowerCase();
        return dimensionScores.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .filter(entry -> {
                    String dimension = entry.getKey().toLowerCase();
                    return dimension.contains(normalizedTopic) || normalizedTopic.contains(dimension);
                })
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(defaultScore);
    }

    /**
     * 根据主题和分数创建薄弱领域，并按分数区间计算优先级。
     *
     * @param topic 薄弱领域名称
     * @param score 薄弱领域得分
     * @return 带有 high、medium 或 low 优先级的薄弱领域
     */
    private ReviewPlan.WeakArea createWeakArea(String topic, double score) {
        String priority = score < 60 ? "high" : score < 75 ? "medium" : "low";
        return ReviewPlan.WeakArea.builder()
                .topic(topic)
                .score(score)
                .priority(priority)
                .build();
    }

    /**
     * 为本地降级计划生成不依赖模型和外部链接的可执行学习项。
     *
     * @param weakArea 需要复习的薄弱领域
     * @return 包含目标、行动步骤和时间估算的学习项
     */
    private ReviewPlan.StudyItem createFallbackStudyItem(ReviewPlan.WeakArea weakArea) {
        String timeEstimate = switch (weakArea.getPriority()) {
            case "high" -> "4 小时";
            case "medium" -> "3 小时";
            default -> "2 小时";
        };
        return ReviewPlan.StudyItem.builder()
                .topic(weakArea.getTopic())
                .objective("补齐 " + weakArea.getTopic() + " 的核心知识并提升实际应用能力")
                .actions(List.of(
                        "复盘面试中与 " + weakArea.getTopic() + " 相关的失分点",
                        "查阅官方文档，整理核心概念和常见误区",
                        "完成 2~3 道相关练习并总结答案"
                ))
                .timeEstimate(timeEstimate)
                .build();
    }

    /**
     * 格式化复习计划为 Markdown
     */
    public static String formatReviewPlan(ReviewPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 个性化复习计划\n\n");

        if (plan.getWeakAreas() != null && !plan.getWeakAreas().isEmpty()) {
            sb.append("## 薄弱领域\n\n");
            sb.append("| 领域 | 得分 | 优先级 |\n|------|------|--------|\n");
            plan.getWeakAreas().forEach(wa ->
                    sb.append(String.format("| %s | %.0f | %s |\n", wa.getTopic(), wa.getScore(), wa.getPriority())));
            sb.append("\n");
        }

        if (plan.getStudyPlan() != null && !plan.getStudyPlan().isEmpty()) {
            sb.append("## 学习计划\n\n");
            for (ReviewPlan.StudyItem item : plan.getStudyPlan()) {
                sb.append(String.format("### %s\n\n", item.getTopic()));
                sb.append(String.format("**目标**：%s\n\n", item.getObjective()));
                sb.append(String.format("**预估时间**：%s\n\n", item.getTimeEstimate()));
                if (item.getActions() != null) {
                    sb.append("**行动步骤**：\n");
                    item.getActions().forEach(a -> sb.append("- ").append(a).append("\n"));
                    sb.append("\n");
                }
            }
        }

        if (plan.getResources() != null && !plan.getResources().isEmpty()) {
            sb.append("## 推荐资源\n\n");
            for (ReviewPlan.Resource res : plan.getResources()) {
                sb.append(String.format("- **%s**（%s）", res.getTitle(), res.getType()));
                if (res.getUrl() != null && !res.getUrl().isEmpty()) {
                    sb.append(String.format("：[链接](%s)", res.getUrl()));
                }
                if (res.getDesc() != null && !res.getDesc().isEmpty()) {
                    sb.append(" — ").append(res.getDesc());
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
