package com.interview.agent.agent;

import com.interview.agent.model.EvaluationReport;
import com.interview.agent.model.ReviewPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReviewPlanner 解析逻辑的单元测试。
 * 重点验证：当 LLM 返回的 JSON 缺字段时，复习计划解析不再抛 NPE（对应实测面试中遇到的偶发崩溃）。
 *
 * @author 陈龙强
 */
class ReviewPlannerTest {

    /**
     * 复现真实场景：LLM 只返回 study_plan，缺 weak_areas 和 resources。
     * 修复前 plan.getWeakAreas().size() 会抛 NullPointerException；
     * 修复后三个列表被规范化为空集合，解析正常返回。
     */
    @Test
    @DisplayName("LLM 漏字段时应规范化为空集合且不抛 NPE")
    void parsePlan_whenLlmOmitsFields_normalizesToEmptyAndNoNpe() {
        ReviewPlanner planner = new ReviewPlanner(null); // parsePlan 不使用 chatModel
        String llmOutput = "{\"study_plan\":[{\"topic\":\"Go 并发\",\"objective\":\"掌握 sync 包\","
                + "\"actions\":[\"读 sync 源码\"],\"time_estimate\":\"4h\"}]}";

        ReviewPlan plan = assertDoesNotThrow(() -> planner.parsePlan(llmOutput, "sess-1"));

        assertNotNull(plan.getWeakAreas(), "weakAreas 应被规范化为非 null");
        assertNotNull(plan.getStudyPlan(), "studyPlan 应非 null");
        assertNotNull(plan.getResources(), "resources 应被规范化为非 null");
        assertEquals(0, plan.getWeakAreas().size());
        assertEquals(0, plan.getResources().size());
        assertEquals(1, plan.getStudyPlan().size());
        assertEquals("sess-1", plan.getSessionId());
        assertNotNull(plan.getCreatedAt());
    }

    /**
     * 极端情况：LLM 返回空对象 {}，三个列表全缺。也应不崩、全部为空集合。
     */
    @Test
    @DisplayName("空 JSON 对象也应安全返回空计划")
    void parsePlan_emptyObject_returnsEmptyPlan() {
        ReviewPlanner planner = new ReviewPlanner(null);

        ReviewPlan plan = assertDoesNotThrow(() -> planner.parsePlan("{}", "sess-empty"));

        assertNotNull(plan.getWeakAreas());
        assertNotNull(plan.getStudyPlan());
        assertNotNull(plan.getResources());
        assertEquals(0, plan.getWeakAreas().size());
    }

    /**
     * 正常完整 JSON 应被正确解析（确保重构没破坏正常路径）。
     */
    @Test
    @DisplayName("完整 JSON 应被正确解析")
    void parsePlan_fullJson_parsedCorrectly() {
        ReviewPlanner planner = new ReviewPlanner(null);
        String llmOutput = "{\"weak_areas\":[{\"topic\":\"MySQL 索引\",\"score\":55.0,\"priority\":\"high\"}],"
                + "\"study_plan\":[{\"topic\":\"索引优化\",\"objective\":\"理解 B+ 树\","
                + "\"actions\":[\"看执行计划\"],\"time_estimate\":\"3h\"}],"
                + "\"resources\":[{\"title\":\"MySQL 官方文档\",\"type\":\"article\",\"url\":\"https://dev.mysql.com\",\"desc\":\"权威\"}]}";

        ReviewPlan plan = planner.parsePlan(llmOutput, "sess-2");

        assertEquals(1, plan.getWeakAreas().size());
        assertEquals("MySQL 索引", plan.getWeakAreas().get(0).getTopic());
        assertEquals("high", plan.getWeakAreas().get(0).getPriority());
        assertEquals(1, plan.getStudyPlan().size());
        assertEquals(1, plan.getResources().size());
        assertEquals("MySQL 官方文档", plan.getResources().get(0).getTitle());
    }

    /** 验证模型服务不可用时可根据报告薄弱项生成可执行的本地计划。 */
    @Test
    @DisplayName("模型不可用时应根据薄弱项生成本地基础计划")
    void buildFallbackPlan_withWeaknesses_createsActionablePlan() {
        ReviewPlanner planner = new ReviewPlanner(null);
        EvaluationReport report = EvaluationReport.builder()
                .sessionId("sess-fallback")
                .overallScore(68)
                .weaknesses(List.of("Java 并发", "MySQL"))
                .dimensionScore(Map.of("Java 并发", 52.0, "MySQL", 70.0))
                .build();

        ReviewPlan plan = planner.buildFallbackPlan(report);

        assertEquals("sess-fallback", plan.getSessionId());
        assertEquals(2, plan.getWeakAreas().size());
        assertEquals("high", plan.getWeakAreas().get(0).getPriority());
        assertEquals(2, plan.getStudyPlan().size());
        assertFalse(plan.getStudyPlan().get(0).getActions().isEmpty());
        assertTrue(plan.getResources().isEmpty(), "本地降级计划不应编造资源链接");
        assertNotNull(plan.getCreatedAt());
    }

    /** 验证报告未给出薄弱项时选择得分最低的三个维度作为复习重点。 */
    @Test
    @DisplayName("报告没有薄弱项时应选取得分最低的三个维度")
    void buildFallbackPlan_withoutWeaknesses_usesLowestDimensions() {
        ReviewPlanner planner = new ReviewPlanner(null);
        EvaluationReport report = EvaluationReport.builder()
                .sessionId("sess-dimensions")
                .overallScore(70)
                .dimensionScore(Map.of(
                        "基础知识", 80.0,
                        "系统设计", 55.0,
                        "项目经验", 62.0,
                        "沟通表达", 75.0
                ))
                .build();

        ReviewPlan plan = planner.buildFallbackPlan(report);

        assertEquals(3, plan.getWeakAreas().size());
        assertEquals("系统设计", plan.getStudyPlan().get(0).getTopic());
        assertEquals("项目经验", plan.getStudyPlan().get(1).getTopic());
        assertEquals("沟通表达", plan.getStudyPlan().get(2).getTopic());
    }

    /** 验证所有模型路径失败时对编排层返回明确的 fallback 标记。 */
    @Test
    @DisplayName("所有模型调用失败时应返回可识别的降级结果")
    void plan_whenAllModelCallsFail_marksResultAsFallback() {
        ReviewPlanner planner = new ReviewPlanner(null);
        EvaluationReport report = EvaluationReport.builder()
                .sessionId("sess-model-unavailable")
                .overallScore(58)
                .weaknesses(List.of("系统设计"))
                .build();

        ReviewPlanner.GenerationResult result = assertDoesNotThrow(() -> planner.plan(report));

        assertTrue(result.fallback());
        assertNotNull(result.plan());
        assertEquals("sess-model-unavailable", result.plan().getSessionId());
        assertEquals("系统设计", result.plan().getWeakAreas().get(0).getTopic());
    }
}
