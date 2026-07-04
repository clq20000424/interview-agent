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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** GitHub 工具（仅配置了 token 时存在）；存在时作为 ReactAgent 的工具注入 */
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

    public ReviewPlan plan(EvaluationReport report) {
        log.info("[ReviewPlanner] 开始生成复习计划");

        String reportJson;
        try {
            reportJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            reportJson = report.toString();
        }
        String userMsg = "## 面试评估报告\n" + reportJson + "\n" + OUTPUT_FORMAT;

        String content = generateWithReactAgent(userMsg);
        if (content == null) {
            // 降级：ReactAgent 不可用时退回单轮生成（无工具）
            content = chatModel.call(new Prompt(List.of(
                    new SystemMessage(REVIEW_PLANNER_INSTRUCTION),
                    new UserMessage(userMsg)
            ))).getResult().getOutput().getText();
        }

        return parsePlan(content, report.getSessionId());
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
            return msg != null ? msg.getText() : null;
        } catch (Exception e) {
            log.warn("[ReviewPlanner] ReactAgent 执行失败，降级为单轮生成: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 格式化复习计划为 Markdown（与 Go 版本 FormatReviewPlan 对齐）
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
