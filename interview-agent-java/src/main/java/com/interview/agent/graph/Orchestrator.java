package com.interview.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.agent.*;
import com.interview.agent.memory.LongTermMemory;
import com.interview.agent.memory.MySQLStore;
import com.interview.agent.memory.ShortTermMemory;
import com.interview.agent.memory.UserProfile;
import com.interview.agent.model.*;
import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.MilvusStore;
import com.interview.agent.rag.RagDocument;
import com.interview.agent.rag.Reranker;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 面试流程编排器 —— 用 Spring AI Alibaba Graph（StateGraph）把 6 阶段编排成有向图。
 * <p>
 * 图结构：
 * <pre>
 *   START → jd_analysis → resume_match → question_plan → interview
 *                                                            │
 *                                       (用户未作答即终止) ──┴── END
 *                                                            │
 *                                          weak_review → evaluation → review_plan → END
 * </pre>
 * 说明：graph 在执行节点前会对 OverAllState 做 Jackson 深拷贝快照，因此<strong>不能</strong>把
 * 回调（含会阻塞的 getUserAnswer）和业务对象塞进 state。这里让 graph 的 state 保持为空、只负责
 * 编排节点的执行顺序与条件分支；面试上下文（输入、各阶段产物、回调）放在一个 per-interview 的
 * {@link Ctx} 持有者里，由各节点闭包捕获共享。对外行为、回调时序、前端消息协议与顺序编排版本一致。
 *
 * @author 陈龙强
 */
@Slf4j
@Component
public class Orchestrator {

    /**
     * 两路 RAG 召回的最大等待时间，超时后该路按空结果降级。
     */
    private static final long RAG_RETRIEVAL_TIMEOUT_SECONDS = 5L;

    private final JDAnalyzer jdAnalyzer;
    private final ResumeMatcher resumeMatcher;
    private final QuestionPlanner questionPlanner;
    private final Interviewer interviewer;
    private final Evaluator evaluator;
    private final ReviewPlanner reviewPlanner;
    private final ShortTermMemory shortTermMem;
    private final LongTermMemory longTermMem;
    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;
    private final Reranker reranker;
    private final MySQLStore mysqlStore;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * RAG 专用有界线程池，避免检索任务占用公共 ForkJoinPool 或无限制创建线程。
     * 队列满时由提交线程执行任务，提供背压并保证请求不会静默丢失。
     */
    private final ExecutorService ragExecutor = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable, "rag-retrieval-worker");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    public Orchestrator(JDAnalyzer jdAnalyzer, ResumeMatcher resumeMatcher,
                        QuestionPlanner questionPlanner, Interviewer interviewer,
                        Evaluator evaluator, ReviewPlanner reviewPlanner,
                        ShortTermMemory shortTermMem, LongTermMemory longTermMem,
                        MilvusStore milvusStore, BM25Manager bm25Manager,
                        Reranker reranker, MySQLStore mysqlStore) {
        this.jdAnalyzer = jdAnalyzer;
        this.resumeMatcher = resumeMatcher;
        this.questionPlanner = questionPlanner;
        this.interviewer = interviewer;
        this.evaluator = evaluator;
        this.reviewPlanner = reviewPlanner;
        this.shortTermMem = shortTermMem;
        this.longTermMem = longTermMem;
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
        this.reranker = reranker;
        this.mysqlStore = mysqlStore;
    }

    /**
     * 关闭 RAG 线程池，避免应用停止时遗留非守护任务。
     */
    @PreDestroy
    private void shutdownRagExecutor() {
        ragExecutor.shutdown();
    }

    /**
     * 执行完整面试流程：构建并编译一张 StateGraph（节点闭包捕获本次面试上下文），驱动它跑完。
     */
    public Session runInterview(String jdText, String resumeText, String userID,
                                InterviewCallbacks cb) throws Exception {
        Ctx c = new Ctx(jdText, resumeText, userID, cb);

        // state 留空（只放一个占位 key，不放业务对象，避免深拷贝序列化）
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> keys = new HashMap<>();
            keys.put("_", new ReplaceStrategy());
            return keys;
        };

        StateGraph graph = new StateGraph(keyStrategyFactory)
                .addNode("jd_analysis", node_async(s -> {
                    jdAnalysis(c);
                    return Map.of();
                }))
                .addNode("resume_match", node_async(s -> {
                    resumeMatch(c);
                    return Map.of();
                }))
                .addNode("question_plan", node_async(s -> {
                    questionPlan(c);
                    return Map.of();
                }))
                .addNode("interview", node_async(s -> {
                    interview(c);
                    return Map.of();
                }))
                .addNode("weak_review", node_async(s -> {
                    weakReview(c);
                    return Map.of();
                }))
                .addNode("evaluation", node_async(s -> {
                    evaluation(c);
                    return Map.of();
                }))
                .addNode("review_plan", node_async(s -> {
                    reviewPlan(c);
                    return Map.of();
                }))
                .addEdge(START, "jd_analysis")
                .addEdge("jd_analysis", "resume_match")
                .addEdge("resume_match", "question_plan")
                .addEdge("question_plan", "interview")
                .addConditionalEdges("interview", edge_async(s -> afterInterview(c)),
                        Map.of("end", END, "continue", "weak_review"))
                .addEdge("weak_review", "evaluation")
                .addEdge("evaluation", "review_plan")
                .addEdge("review_plan", END);

        CompiledGraph compiledGraph = graph.compile();
        log.info("[Orchestrator] 面试流程 StateGraph 已编译，开始执行");
        compiledGraph.invoke(new HashMap<>());
        log.info("[Orchestrator] 面试流程 StateGraph 执行完成");

        return c.session;
    }

    // ============================================================
    // 各阶段节点（读写 Ctx，调回调推送前端）
    // ============================================================

    /**
     * 阶段 1：JD 分析
     */
    private void jdAnalysis(Ctx c) {
        c.session = new Session();
        c.session.setId(UUID.randomUUID().toString());
        c.session.setUserId(c.userID);
        c.session.setStatus(Session.STATUS_INIT);
        c.session.setCreatedAt(LocalDateTime.now());

        c.cb.onStageChange("jd_analysis", "正在分析岗位 JD...");

        c.jdAnalysis = jdAnalyzer.analyze(c.jdText);
        c.session.setJdAnalysis(c.jdAnalysis);
        c.session.setStatus(Session.STATUS_JD_ANALYZED);

        c.cb.onStageChange("jd_analysis_done",
                String.format("JD 分析完成：%s - %s", c.jdAnalysis.getPosition(), c.jdAnalysis.getExperienceLevel()));
    }

    /**
     * 阶段 2：简历匹配
     */
    private void resumeMatch(Ctx c) {
        c.cb.onStageChange("resume_match", "正在分析简历匹配度...");

        c.resume = new Resume();
        c.resume.setRawText(c.resumeText);

        c.matchResult = resumeMatcher.match(c.jdAnalysis, c.resume);
        c.session.setMatchResult(c.matchResult);
        c.session.setStatus(Session.STATUS_RESUME_MATCHED);

        // 调用新的简历匹配结果回调，发送详细信息
        c.cb.onResumeMatch(c.matchResult);
    }

    /**
     * 阶段 2.5 + 3：读取历史薄弱点 + 出题规划（Phase1 方向 + Phase2 检索/组装）
     */
    private void questionPlan(Ctx c) {
        String userID = c.userID;

        // ===== 阶段 2.5：读取历史薄弱点 =====
        String weakPointsContext = "";
        List<UserProfile.WeakPoint> weakPoints = longTermMem.getWeakPoints(userID);
        if (weakPoints != null && !weakPoints.isEmpty()) {
            List<String> jdSkills = collectJDSkills(c.jdAnalysis);
            List<String> wpLines = new ArrayList<>();
            for (UserProfile.WeakPoint wp : weakPoints) {
                if (isWeakPointRelevant(wp.getTopic(), jdSkills)) {
                    wpLines.add(String.format("- %s：历史得分 %.0f，被考察 %d 次，答错 %d 次",
                            wp.getTopic(), wp.getScore(), wp.getHitCount(), wp.getWrongCount()));
                }
            }
            if (!wpLines.isEmpty()) {
                weakPointsContext = String.join("\n", wpLines);
                String weakPointSummary = String.format("已加载 %d 个与当前 JD 相关的历史薄弱点，将针对性出题",
                        wpLines.size());
                c.cb.onStageChange("memory_loaded", weakPointSummary);
                c.cb.onRelevantWeakPoints(weakPointSummary, wpLines);
            }
        }

        // ===== 阶段 3 Phase 1：规划出题方向 =====
        c.cb.onStageChange("question_plan", "正在规划出题方向...");

        QuestionDirectionPlan dirPlan = questionPlanner.planDirections(c.jdAnalysis, c.matchResult, weakPointsContext);
        log.info("[Plan] Phase 1 完成，规划了 {} 个出题方向", dirPlan.getDirections().size());

        // ===== 阶段 3 Phase 2：按方向检索题库 + 组装题目 =====
        boolean hasRAG = milvusStore != null || bm25Manager != null;
        List<PlannedQuestion> matchedQuestions = new ArrayList<>();
        List<QuestionDirection> unmatchedDirs = new ArrayList<>();
        int matchedCount = 0;

        if (hasRAG) {
            c.cb.onStageChange("rag_retrieval", "正在按出题方向从知识库检索匹配题目...");

            for (int i = 0; i < dirPlan.getDirections().size(); i++) {
                QuestionDirection dir = dirPlan.getDirections().get(i);

                // experience 和 design 类不从题库检索
                if (!"basic".equals(dir.getType())) {
                    unmatchedDirs.add(dir);
                    continue;
                }

                String query = dir.getSearchQuery() != null && !dir.getSearchQuery().isEmpty()
                        ? dir.getSearchQuery() : dir.getTopic();
                log.info("[RAG] 方向 {} 检索: query={}", i + 1, query);

                List<RagDocument> docs = new ArrayList<>();
                Set<String> seen = new HashSet<>();

                // Milvus 和 BM25 相互独立，使用专用线程池并行召回；单路失败或超时只影响自身结果。
                long retrievalStart = System.nanoTime();
                CompletableFuture<List<RagDocument>> milvusFuture = submitRagRetrieval(
                        "Milvus", i + 1,
                        () -> milvusStore == null
                                ? Collections.emptyList()
                                : milvusStore.retrieveByUser(userID, query, 10));

                CompletableFuture<List<RagDocument>> bm25Future = submitRagRetrieval(
                        "BM25", i + 1,
                        () -> bm25Manager == null
                                ? Collections.emptyList()
                                : bm25Manager.retrieve(userID, query));

                CompletableFuture.allOf(milvusFuture, bm25Future).join();
                addUniqueDocuments(docs, seen, milvusFuture.join());
                addUniqueDocuments(docs, seen, bm25Future.join());
                log.info("[RAG] 方向 {} 双路召回完成: milvus={}, bm25={}, merged={}, elapsedMs={}",
                        i + 1, milvusFuture.join().size(), bm25Future.join().size(), docs.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - retrievalStart));

                if (!docs.isEmpty()) {
                    // Rerank 取 top 1
                    List<RagDocument> reranked = reranker.rerank(query, docs);
                    if (reranked == null || reranked.isEmpty()) reranked = docs;
                    RagDocument topDoc = reranked.getFirst();
                    log.info("[RAG] 方向 {} 匹配到题库原题 [{}]", i + 1, topDoc.getId());

                    String questionContent = topDoc.getContent();
                    String reference = "";
                    int refIdx = questionContent.indexOf("\n参考答案：");
                    if (refIdx >= 0) {
                        reference = questionContent.substring(refIdx + "\n参考答案：".length()).trim();
                        questionContent = questionContent.substring(0, refIdx).trim();
                    }

                    PlannedQuestion pq = new PlannedQuestion();
                    pq.setId("q" + (i + 1));
                    pq.setContent(questionContent);
                    pq.setType(dir.getType());
                    pq.setDifficulty(dir.getDifficulty());
                    pq.setSkills(dir.getSkills());
                    pq.setFollowUps(List.of());
                    pq.setReference(reference);
                    pq.setSource(topDoc.getId());
                    matchedQuestions.add(pq);
                    matchedCount++;
                } else {
                    log.info("[RAG] 方向 {} 无匹配题目，交给 LLM", i + 1);
                    unmatchedDirs.add(dir);
                }
            }

            c.cb.onStageChange("rag_retrieval_done",
                    String.format("题库检索完成，%d 道用原题，%d 道由 LLM 出题", matchedCount, unmatchedDirs.size()));
        } else {
            unmatchedDirs.addAll(dirPlan.getDirections());
        }

        // 未匹配的方向交给 LLM 出题
        List<PlannedQuestion> llmQuestions = new ArrayList<>();
        if (!unmatchedDirs.isEmpty()) {
            String directionSummary = String.format("正在为 %d 个方向生成面试题目...", unmatchedDirs.size());
            c.cb.onStageChange("question_assemble", directionSummary);
            QuestionDirectionPlan unmatchedPlan = new QuestionDirectionPlan();
            unmatchedPlan.setDirections(unmatchedDirs);
            c.cb.onQuestionDirections(directionSummary, unmatchedPlan);
            List<String> emptyDocs = Collections.nCopies(unmatchedDirs.size(), "");
            QuestionPlan assembled = questionPlanner.assembleQuestions(c.jdAnalysis, c.matchResult, unmatchedPlan, emptyDocs);
            if (assembled != null && assembled.getQuestions() != null) {
                llmQuestions.addAll(assembled.getQuestions());
            }
        }

        // 合并题目
        List<PlannedQuestion> allQuestions = new ArrayList<>(matchedQuestions);
        allQuestions.addAll(llmQuestions);
        for (int i = 0; i < allQuestions.size(); i++) {
            allQuestions.get(i).setId("q" + (i + 1));
        }

        // 统计分布
        int basicCount = 0, expCount = 0, designCount = 0;
        for (PlannedQuestion q : allQuestions) {
            switch (q.getType() != null ? q.getType() : "") {
                case "basic" -> basicCount++;
                case "experience" -> expCount++;
                case "design" -> designCount++;
            }
        }

        QuestionPlan plan = new QuestionPlan();
        plan.setTotalQuestions(allQuestions.size());
        plan.setDistribution(new QuestionPlan.QuestionDistrib(basicCount, expCount, designCount));
        plan.setQuestions(allQuestions);
        c.questionPlan = plan;
        c.session.setQuestionPlan(plan);
        c.session.setStatus(Session.STATUS_PLANNED);

        String planSummary = String.format("出题计划完成，共 %d 道题（基础%d/经历%d/设计%d）",
                plan.getTotalQuestions(), basicCount, expCount, designCount);
        c.cb.onStageChange("question_plan_done", planSummary);
        c.cb.onQuestionPlan(planSummary, plan);
    }

    /**
     * 阶段 4：模拟面试（含追问、动态难度调节、薄弱点更新，人在环阻塞交互）
     */
    private void interview(Ctx c) {
        List<PlannedQuestion> allQuestions = c.questionPlan.getQuestions();

        c.cb.onStageChange("interview", "面试正式开始！");

        // 面试分三个阶段顺序进行：basic → experience → design。
        // 阶段化取题与阶段内难度调节由 StageScheduler 负责（见 StageScheduler.java）：
        // 每阶段从候选池按当前难度自适应抽取固定道数；进入新阶段时难度重置为 medium，不继承上一阶段。
        StageScheduler sched = new StageScheduler(StageScheduler.DEFAULT_STAGES, allQuestions,
                (cur, consecRight, consecWrong) -> questionPlanner.adjustDifficulty(
                        InterviewState.builder()
                                .currentDifficulty(cur)
                                .consecutiveRight(consecRight)
                                .consecutiveWrong(consecWrong)
                                .build()));

        InterviewState state = new InterviewState();
        state.setSessionId(c.session.getId());
        state.setTotalQuestions(sched.totalToAsk());
        state.setCurrentDifficulty("medium");
        state.setQaHistory(new ArrayList<>());
        c.interviewState = state;
        c.session.setInterviewState(state);
        c.session.setStatus(Session.STATUS_INTERVIEWING);

        boolean userTerminated = false;
        int asked = 0;
        while (true) {
            StageScheduler.Picked picked = sched.next();
            if (picked == null) {
                break;
            }
            PlannedQuestion q = picked.question();
            asked++;
            state.setCurrentQuestion(asked);
            state.setCurrentDifficulty(picked.difficulty());
            log.info("[难度调节] 第{}题 type={} 抽取难度={} (上一题后 连对{}/连错{}) 来源={}",
                    asked, q.getType(), picked.difficulty(), sched.getConsecRight(), sched.getConsecWrong(), q.getSource());

            // 面试官提问
            String questionText = interviewer.askQuestion(state, q, c.jdAnalysis.getPosition());
            if (q.getSource() != null && !q.getSource().isEmpty() && !"llm".equals(q.getSource())) {
                questionText += String.format("\n\n`[来源: 题库 %s]`", q.getSource());
            } else {
                questionText += "\n\n`[来源: LLM 出题]`";
            }

            c.cb.onQuestion(asked, questionText);

            // 等待用户回答
            String answer;
            try {
                answer = c.cb.getUserAnswer();
            } catch (UserQuitException e) {
                userTerminated = true;
                c.cb.onStageChange("terminated",
                        String.format("用户主动终止面试（已完成 %d/%d 题）", state.getQaHistory().size(), state.getTotalQuestions()));
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                userTerminated = true;
                break;
            }

            // 评分
            AnswerScore score = interviewer.scoreAnswer(q, answer);
            c.cb.onScore(score);

            // 更新候选人动态画像
            try {
                String updatedProfile = interviewer.updateCandidateProfile(
                        state.getCandidateProfile(), asked, q, score);
                state.setCandidateProfile(updatedProfile);
            } catch (Exception e) {
                log.warn("[Profile] 画像更新失败（不影响主流程）: {}", e.getMessage());
            }

            // 记录问答
            QAPair qa = new QAPair();
            qa.setQuestion(q);
            qa.setUserAnswer(answer);
            qa.setScore(score.getScore());
            qa.setFeedback(score.getFeedback());

            // 追问逻辑
            boolean shouldFollowUp = score.isShouldFollowUp()
                    && score.getScore() >= 30 && score.getScore() < 80
                    && score.getKeyPointsMissed() != null && !score.getKeyPointsMissed().isEmpty();

            if (shouldFollowUp) {
                try {
                    String followUpText = interviewer.followUp(state, q, answer,
                            score.getFeedback(), score.getKeyPointsMissed(), c.jdAnalysis.getPosition());
                    c.cb.onQuestion(asked, "[追问] " + followUpText);

                    try {
                        String followUpAnswer = c.cb.getUserAnswer();
                        qa.setFollowUpUsed(true);
                        qa.setUserAnswer(qa.getUserAnswer() + "\n[追问回答] " + followUpAnswer);

                        AnswerScore followUpScore = interviewer.scoreAnswer(q, followUpAnswer);
                        c.cb.onScore(followUpScore);
                    } catch (UserQuitException e) {
                        state.getQaHistory().add(qa);
                        userTerminated = true;
                        c.cb.onStageChange("terminated",
                                String.format("用户主动终止面试（已完成 %d/%d 题）",
                                        state.getQaHistory().size(), state.getTotalQuestions()));
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        state.getQaHistory().add(qa);
                        userTerminated = true;
                        break;
                    }
                } catch (Exception e) {
                    log.warn("[Interviewer] 追问失败: {}", e.getMessage());
                }
            }

            state.getQaHistory().add(qa);

            // 动态难度调节（阶段内，由 scheduler 维护；同步到 state 供报告/前端展示）
            sched.record(score.getScore());
            state.setConsecutiveRight(sched.getConsecRight());
            state.setConsecutiveWrong(sched.getConsecWrong());

            // 更新薄弱点（best-effort：记忆写入失败不应中断面试主流程）
            if (q.getSkills() != null) {
                try {
                    for (String skill : q.getSkills()) {
                        longTermMem.updateWeakPoints(c.userID, skill, score.getScore());
                    }
                } catch (Exception e) {
                    log.warn("[Orchestrator] 更新薄弱点失败（不影响主流程）: {}", e.getMessage());
                }
            }
        }

        c.userTerminated = userTerminated;

        // 终止时设置 session 状态（与顺序版本一致）
        if (userTerminated && state.getQaHistory().isEmpty()) {
            c.session.setStatus(Session.STATUS_TERMINATED);
            c.session.setUpdatedAt(LocalDateTime.now());
            c.cb.onStageChange("completed", "面试未作答即终止，不生成评估报告。");
        } else if (userTerminated) {
            c.session.setStatus(Session.STATUS_TERMINATED);
        }
    }

    /**
     * interview 之后的分支：用户未作答即终止 → 直接结束（不生成报告）；否则进入低分巩固/评估。
     */
    private String afterInterview(Ctx c) {
        InterviewState state = c.interviewState;
        if (c.userTerminated && (state.getQaHistory() == null || state.getQaHistory().isEmpty())) {
            return "end";
        }
        return "continue";
    }

    /**
     * 阶段 4.5：低分题目巩固
     */
    private void weakReview(Ctx c) {
        InterviewState state = c.interviewState;
        if (state.getQaHistory() != null && !state.getQaHistory().isEmpty()) {
            List<QAPair> weakQAs = state.getQaHistory().stream()
                    .filter(qa -> qa.getScore() < 60)
                    .toList();

            if (!weakQAs.isEmpty()) {
                c.cb.onStageChange("review_weak",
                        String.format("正在对 %d 道低分题目进行巩固...", weakQAs.size()));

                for (int idx = 0; idx < weakQAs.size(); idx++) {
                    QAPair qa = weakQAs.get(idx);
                    String reviewContent = buildWeakReviewContent(idx, weakQAs.size(), qa, c.userID);
                    if (reviewContent != null) {
                        c.cb.onReviewItem(reviewContent);
                    }
                }

                c.cb.onStageChange("review_weak_done", "低分题目巩固完成");
            }
        }
    }

    /**
     * 阶段 5：生成评估报告
     */
    private void evaluation(Ctx c) {
        InterviewState state = c.interviewState;

        if (c.userTerminated) {
            c.cb.onStageChange("evaluation",
                    String.format("面试提前终止，正在基于已完成的 %d 道题生成评估报告...",
                            state.getQaHistory().size()));
        } else {
            c.cb.onStageChange("evaluation", "正在生成评估报告...");
            c.session.setStatus(Session.STATUS_EVALUATED);
        }

        c.report = evaluator.evaluate(state, c.jdAnalysis.getPosition(),
                c.resume != null ? c.resume.getName() : null, c.userTerminated);
        c.session.setReport(c.report);

        String reportMD = Evaluator.formatReport(c.report);
        c.cb.onReport(reportMD);
    }

    /**
     * 阶段 6：生成复习计划并持久化面试记录。模型路径全部失败时继续使用本地基础计划，
     * 同时通过阶段回调向前端明确发送降级原因和本次评估的薄弱主题。
     *
     * @param c 本次面试上下文，包含评估报告、回调、用户及持久化数据
     */
    private void reviewPlan(Ctx c) {
        c.cb.onStageChange("review_plan", "正在生成复习计划...");

        ReviewPlanner.GenerationResult generationResult = reviewPlanner.plan(c.report);
        c.reviewPlan = generationResult.plan();
        if (generationResult.fallback()) {
            String weakTopics = c.reviewPlan.getWeakAreas().stream()
                    .map(ReviewPlan.WeakArea::getTopic)
                    .filter(Objects::nonNull)
                    .filter(topic -> !topic.isBlank())
                    .limit(3)
                    .collect(Collectors.joining("、"));
            String fallbackMessage = weakTopics.isEmpty()
                    ? "模型服务暂时不可用，已根据本次面试评估生成基础复习计划；推荐资源暂缺。"
                    : String.format("模型服务暂时不可用，已根据本次评估薄弱项（%s）生成基础复习计划；推荐资源暂缺。", weakTopics);
            c.cb.onStageChange("review_plan_fallback", fallbackMessage);
        }
        c.session.setReviewPlan(c.reviewPlan);
        c.session.setStatus(Session.STATUS_COMPLETED);
        c.session.setUpdatedAt(LocalDateTime.now());

        String planMD = ReviewPlanner.formatReviewPlan(c.reviewPlan);
        c.cb.onReviewPlan(planMD);

        // ===== 持久化面试记录 =====
        longTermMem.addInterviewRecord(c.userID, UserProfile.InterviewRecord.builder()
                .sessionId(c.session.getId())
                .position(c.jdAnalysis.getPosition())
                .overallScore(c.report.getOverallScore())
                .date(LocalDateTime.now())
                .build());

        if (mysqlStore != null) {
            try {
                String reportJSON = objectMapper.writeValueAsString(c.report);
                String planJSON = objectMapper.writeValueAsString(c.reviewPlan);
                mysqlStore.saveInterviewRecord(c.userID, UserProfile.InterviewRecord.builder()
                        .sessionId(c.session.getId())
                        .position(c.jdAnalysis.getPosition())
                        .overallScore(c.report.getOverallScore())
                        .date(LocalDateTime.now())
                        .build(), reportJSON, planJSON);
            } catch (Exception e) {
                log.warn("[Orchestrator] 保存面试记录到 MySQL 失败: {}", e.getMessage());
            }
        }

        c.cb.onStageChange("completed", "面试流程全部完成！");
    }

    // ============================================================
    // 面试上下文持有者（不进入 graph state，避免被序列化）
    // ============================================================
    private static final class Ctx {
        final String jdText;
        final String resumeText;
        final String userID;
        final InterviewCallbacks cb;

        Session session;
        JDAnalysis jdAnalysis;
        Resume resume;
        ResumeMatchResult matchResult;
        QuestionPlan questionPlan;
        InterviewState interviewState;
        EvaluationReport report;
        ReviewPlan reviewPlan;
        boolean userTerminated;

        Ctx(String jdText, String resumeText, String userID, InterviewCallbacks cb) {
            this.jdText = jdText;
            this.resumeText = resumeText;
            this.userID = userID;
            this.cb = cb;
        }
    }

    // ============================================================
    // 辅助方法（与顺序版本一致）
    // ============================================================

    /**
     * 构建低分题巩固内容
     */
    private String buildWeakReviewContent(int idx, int total, QAPair qa, String userID) {
        PlannedQuestion question = qa.getQuestion();
        String source = question.getSource();

        if (source != null && !source.isEmpty() && !"llm".equals(source)) {
            // 题库出题：优先用参考答案
            String refAnswer = question.getReference();
            if ((refAnswer == null || refAnswer.isEmpty()) && (milvusStore != null || bm25Manager != null)) {
                refAnswer = retrieveReferenceAnswer(userID, question.getContent());
            }
            if (refAnswer != null && !refAnswer.isEmpty()) {
                return String.format("**低分题目巩固 %d/%d**\n\n**题目：** %s\n\n**你的得分：** %.0f\n\n**题库参考答案：**\n%s",
                        idx + 1, total, question.getContent(), qa.getScore(), refAnswer);
            }
        } else if (question.getReference() != null && !question.getReference().isEmpty()) {
            return String.format("**低分题目巩固 %d/%d**\n\n**题目：** %s\n\n**你的得分：** %.0f\n\n**参考答案：**\n%s",
                    idx + 1, total, question.getContent(), qa.getScore(), question.getReference());
        }
        return null;
    }

    private String retrieveReferenceAnswer(String userID, String query) {
        try {
            Set<String> seen = new HashSet<>();
            List<RagDocument> docs = new ArrayList<>();

            if (milvusStore != null) {
                for (RagDocument doc : milvusStore.retrieveByUser(userID, query, 3)) {
                    if (!seen.contains(doc.getId())) {
                        seen.add(doc.getId());
                        docs.add(doc);
                    }
                }
            }
            if (bm25Manager != null) {
                for (RagDocument doc : bm25Manager.retrieve(userID, query)) {
                    if (!seen.contains(doc.getId())) {
                        seen.add(doc.getId());
                        docs.add(doc);
                    }
                }
            }

            if (!docs.isEmpty()) {
                String content = docs.getFirst().getContent();
                int refIdx = content.indexOf("\n参考答案：");
                if (refIdx >= 0) {
                    return content.substring(refIdx + "\n参考答案：".length()).trim();
                }
            }
        } catch (Exception e) {
            log.warn("[Orchestrator] 检索参考答案失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 提交一条带超时和异常降级的 RAG 召回任务。
     * 超时或后端异常时返回空列表，避免单路检索阻断整次面试出题。
     */
    private CompletableFuture<List<RagDocument>> submitRagRetrieval(
            String backend, int direction, Supplier<List<RagDocument>> retrieval) {
        long start = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> {
                    List<RagDocument> documents = retrieval.get();
                    return documents == null ? Collections.<RagDocument>emptyList() : documents;
                }, ragExecutor)
                .orTimeout(RAG_RETRIEVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    log.warn("[RAG] {} 检索失败（方向{}，elapsedMs={}）: {}", backend, direction,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), cause.getMessage());
                    return Collections.emptyList();
                })
                .whenComplete((documents, error) -> {
                    if (error == null) {
                        log.info("[RAG] {} 检索完成（方向{}）: count={}, elapsedMs={}", backend, direction,
                                documents.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    }
                });
    }

    /**
     * 按召回来源顺序合并结果并去重，保持 Milvus 优先、BM25 补充的原有排序语义。
     */
    private static void addUniqueDocuments(List<RagDocument> documents,
                                           Set<String> seen,
                                           List<RagDocument> candidates) {
        if (candidates == null) {
            return;
        }
        for (RagDocument candidate : candidates) {
            if (candidate != null && seen.add(candidate.getId())) {
                documents.add(candidate);
            }
        }
    }

    /**
     * 收集 JD 中的所有技能关键词（小写）
     */
    static List<String> collectJDSkills(JDAnalysis jd) {
        List<String> skills = new ArrayList<>();
        if (jd.getRequiredSkills() != null) {
            jd.getRequiredSkills().forEach(s -> skills.add(s.getName().toLowerCase()));
        }
        if (jd.getPreferredSkills() != null) {
            jd.getPreferredSkills().forEach(s -> skills.add(s.getName().toLowerCase()));
        }
        if (jd.getKeyTopics() != null) {
            jd.getKeyTopics().forEach(t -> skills.add(t.toLowerCase()));
        }
        return skills;
    }

    /**
     * 判断薄弱点是否和当前 JD 技能相关
     */
    static boolean isWeakPointRelevant(String topic, List<String> jdSkills) {
        String topicLower = topic.toLowerCase();
        for (String skill : jdSkills) {
            if (topicLower.contains(skill) || skill.contains(topicLower)) {
                return true;
            }
        }
        return false;
    }
}
