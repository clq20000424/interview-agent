package com.interview.agent.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.agent.ChatAgent;
import com.interview.agent.agent.IntentRouter;
import com.interview.agent.auth.JwtService;
import com.interview.agent.graph.InterviewCallbacks;
import com.interview.agent.graph.Orchestrator;
import com.interview.agent.graph.UserQuitException;
import com.interview.agent.loader.DocumentLoader;
import com.interview.agent.loader.QuestionParser;
import com.interview.agent.loader.WebLoader;
import com.interview.agent.memory.RedisStore;
import com.interview.agent.model.*;
import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.MilvusStore;
import com.interview.agent.rag.RagDocument;
import com.interview.agent.repository.SessionRepository;
import com.interview.agent.service.SessionCacheService;
import com.interview.agent.skill.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * WebSocket 处理器
 * - handleChat：3 级优先级（active skill → skill match → ChatAgent）
 * - handleStartInterview：创建 Orchestrator，异步运行面试
 * - handleAnswer：通过 answerCh 传递用户回答
 * - handleUploadQuestions：base64 解码 → SHA256 去重 → LLM 解析 → Milvus + BM25
 * - handleQuitInterview：用户主动终止
 *
 * @author 陈龙强
 */
@Slf4j
@Component
public class WebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Orchestrator orchestrator;
    private final ChatAgent chatAgent;
    private final IntentRouter intentRouter;
    private final SkillRegistry skillRegistry;
    private final DocumentLoader documentLoader;
    private final QuestionParser questionParser;
    private final WebLoader webLoader;
    private final MilvusStore milvusStore;
    private final BM25Manager bm25Manager;
    private final RedisStore redisStore;
    private final JwtService jwtService;
    private final ChatModel chatModel;
    private final SessionRepository sessionRepository;
    private final SessionCacheService sessionCacheService;

    /**
     * session 管理
     */
    private final Map<String, WSSession> sessions = new ConcurrentHashMap<>();

    /**
     * 运行中的面试按业务 sessionId 建索引，用于页面刷新后把新 WebSocket 重新挂回原面试线程。
     */
    private final Map<String, WSSession> runningInterviews = new ConcurrentHashMap<>();

    /**
     * 异步任务线程池（面试流程 / 题库上传）。面试节点会阻塞等待用户回答，必须与 WebSocket
     * 消息处理线程隔离，避免长任务占住连接线程。
     */
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "interview-async-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * 注入 WebSocket 处理器依赖的编排器、聊天 Agent、RAG 组件、认证服务和会话仓库。
     */
    public WebSocketHandler(Orchestrator orchestrator, ChatAgent chatAgent,
                            IntentRouter intentRouter, SkillRegistry skillRegistry,
                            DocumentLoader documentLoader, QuestionParser questionParser,
                            WebLoader webLoader, MilvusStore milvusStore,
                            BM25Manager bm25Manager, RedisStore redisStore,
                            JwtService jwtService, ChatModel chatModel,
                            SessionRepository sessionRepository,
                            SessionCacheService sessionCacheService) {
        this.orchestrator = orchestrator;
        this.chatAgent = chatAgent;
        this.intentRouter = intentRouter;
        this.skillRegistry = skillRegistry;
        this.documentLoader = documentLoader;
        this.questionParser = questionParser;
        this.webLoader = webLoader;
        this.milvusStore = milvusStore;
        this.bm25Manager = bm25Manager;
        this.redisStore = redisStore;
        this.jwtService = jwtService;
        this.chatModel = chatModel;
        this.sessionRepository = sessionRepository;
        this.sessionCacheService = sessionCacheService;
    }

    /**
     * WebSocket 会话状态
     */
    private static class WSSession {
        WebSocketSession conn;
        String userID;
        List<org.springframework.ai.chat.messages.Message> chatHistory = new ArrayList<>();
        Skill activeSkill;
        SkillState skillState;
        Session chatSession;
        BlockingQueue<String> answerCh = new LinkedBlockingQueue<>();
        volatile boolean interviewRunning = false;
    }

    /**
     * WebSocket 建连后校验 query 中的 JWT，并为当前连接初始化服务端会话状态。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 从 URI query 解析 token
        String query = session.getUri() != null ? session.getUri().getQuery() : "";
        String token = "";
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }
        }

        String userID = "anonymous";
        if (!token.isEmpty()) {
            try {
                userID = jwtService.validateToken(token);
            } catch (JwtService.TokenExpiredException e) {
                log.warn("[WS] token 已过期: {}", e.getMessage());
                sendServerMsg(session, ServerMsg.builder()
                        .type("error")
                        .message("Token 已过期，请重新登录")
                        .build());
                closeSession(session, "token_expired");
                return;
            } catch (Exception e) {
                log.warn("[WS] token 验证失败: {}", e.getMessage());
                sendServerMsg(session, ServerMsg.builder()
                        .type("error")
                        .message("Token 无效，请重新登录")
                        .build());
                closeSession(session, "token_invalid");
                return;
            }
        }

        WSSession ws = new WSSession();
        ws.conn = session;
        ws.userID = userID;
        sessions.put(session.getId(), ws);

        log.info("[WS] 用户 {} 已连接 (sessionId={})", userID, session.getId());
        sendServerMsg(session, ServerMsg.builder().type("connected").content("连接成功").build());
    }

    /**
     * 关闭 WebSocket 会话
     */
    private void closeSession(WebSocketSession session, String reason) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
            }
        } catch (Exception e) {
            log.error("[WS] 关闭会话失败: {}", e.getMessage());
        }
    }

    /**
     * WebSocket 连接关闭后清理内存中的连接状态。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, @NotNull CloseStatus status) {
        sessions.remove(session.getId());
        log.info("[WS] 连接关闭 (sessionId={})", session.getId());
    }

    /**
     * 接收并解析客户端 WebSocket 消息，按消息类型分发到对应业务处理方法。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, @NotNull TextMessage message) {
        WSSession ws = sessions.get(session.getId());
        if (ws == null) return;

        try {
            ClientMsg msg = objectMapper.readValue(message.getPayload(), ClientMsg.class);

            switch (msg.getType() != null ? msg.getType() : "") {
                case "chat" -> handleChat(ws, msg);
                case "start_interview" -> handleStartInterview(ws, msg);
                case "answer" -> handleAnswer(ws, msg);
                case "upload_questions" -> handleUploadQuestions(ws, msg);
                case "quit_interview" -> handleQuitInterview(ws, msg);
                case "new_chat" -> handleNewChat(ws);
                case "load_session" -> handleLoadSession(ws, msg);
                default -> sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("error")
                        .message("未知消息类型: " + msg.getType())
                        .build());
            }
        } catch (Exception e) {
            log.error("[WS] 处理消息异常: {}", e.getMessage(), e);
            sendServerMsg(session, ServerMsg.builder().type("error").message("处理消息异常: " + e.getMessage()).build());
        }
    }

    /**
     * 聊天消息处理：3 级优先级
     * 1. 已激活的 Skill 继续处理
     * 2. SkillRegistry 匹配新 Skill
     * 3. ChatAgent 兜底
     */
    private void handleChat(WSSession ws, ClientMsg msg) {
        String input = resolveInput(msg.getContent() != null ? msg.getContent() : "");

        // 优先级 1：已激活的 Skill
        if (ws.activeSkill != null) {
            if (SkillUtils.isQuitCommand(input) || (ws.skillState != null && ws.skillState.isExpired())) {
                ws.activeSkill = null;
                ws.skillState = null;
                sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content("已退出技能模式。").build());
                return;
            }

            // 增强：技能会话进行中，若用户又发出明确的新技能/新测验意图
            // （例如测验做到一半再说「来几道 mysql 面试题」），则结束当前会话、切换到新技能，
            // 而不是把这句话当成当前题目的回答。普通的答题内容不含触发词，不会被误切。
            if (skillRegistry.match(input) == null) {
                SkillResponse resp = ws.activeSkill.handle(input, ws.skillState);
                ws.skillState = resp.getState();
                if (resp.isDone()) {
                    ws.activeSkill = null;
                    ws.skillState = null;
                }
                sendChatReply(ws, input, resp.getContent());
                return;
            }
            // 命中新的技能意图：清空当前会话，落到下方「优先级 2」开启新技能
            ws.activeSkill = null;
            ws.skillState = null;
        }

        // 优先级 2：匹配新 Skill
        Skill matched = skillRegistry.match(input);
        if (matched != null) {
            ws.activeSkill = matched;
            ws.skillState = SkillState.create(matched.name());
            ws.skillState.setUserId(ws.userID);

            SkillResponse resp = matched.handle(input, ws.skillState);
            ws.skillState = resp.getState();
            if (resp.isDone()) {
                ws.activeSkill = null;
                ws.skillState = null;
            }
            sendChatReply(ws, input, resp.getContent());
            return;
        }

        // 优先级 3：ChatAgent 兜底
        String reply = chatAgent.chat(ws.chatHistory, input);
        ws.chatHistory.add(new UserMessage(input));
        ws.chatHistory.add(new AssistantMessage(reply));
        trimChatHistory(ws);
        sendChatReply(ws, input, reply);
    }

    /**
     * 启动完整面试流程。优先把当前用户的普通聊天 Session 升级为面试并复用原 ID；
     * 没有可复用会话时才创建新 Session。面试前消息会在流程结束时与 Redis 面试消息合并持久化。
     *
     * @param ws  当前 WebSocket 业务会话
     * @param msg 包含 JD、简历和可选当前 Session ID 的客户端消息
     */
    private void handleStartInterview(WSSession ws, ClientMsg msg) {
        if (ws.interviewRunning) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("面试已在进行中").build());
            return;
        }

        String jdText = msg.getJd() != null ? msg.getJd() : "";
        String resumeText = msg.getResume() != null ? msg.getResume() : "";

        if (jdText.isEmpty() || resumeText.isEmpty()) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("JD 和简历不能为空").build());
            return;
        }

        // 解析 JD / 简历输入：支持 [FILE:] 文件、URL 抓取、纯文本
        jdText = resolveInput(jdText);
        resumeText = resolveInput(resumeText);

        ws.interviewRunning = true;
        ws.answerCh = new LinkedBlockingQueue<>();

        String finalJdText = jdText;
        String finalResumeText = resumeText;

        // 从普通聊天进入面试时复用原 Session，避免历史列表出现一条聊天和一条面试记录。
        Session currentChatSession = resolveChatSessionForInterview(ws, msg.getSessionId());
        Session pendingSession = preparePendingInterviewSession(currentChatSession, ws.userID);
        String sessionId = pendingSession.getId();
        List<ConversationMessage> preInterviewMessages = copyMessages(pendingSession.getChatMessages());
        LocalDateTime originalCreatedAt = pendingSession.getCreatedAt();
        Boolean originalPinned = pendingSession.getPinned();
        LocalDateTime originalPinnedAt = pendingSession.getPinnedAt();

        // 面试执行期间暂停普通聊天关联，结束持久化后再把同一 Session 挂回去继续对话。
        ws.chatSession = null;
        ws.chatHistory.clear();
        ws.activeSkill = null;
        ws.skillState = null;
        runningInterviews.put(sessionId, ws);

        // 先落一条进行中的会话索引。页面刷新时 /api/sessions/active 依赖它找到 Redis field。
        try {
            sessionRepository.save(pendingSession);
            notifySessionsChanged(ws);
        } catch (Exception e) {
            log.warn("[WS] 保存进行中会话索引失败，将仅依赖Redis缓存恢复: sessionId={}, userId={}, error={}",
                    sessionId, ws.userID, e.getMessage());
        }

        // 通知前端当前会话 ID，前端收到后保存到 chatStore.currentSessionId
        sendServerMsg(ws.conn, ServerMsg.builder()
                .type("session_started")
                .content(sessionId)
                .build());

        asyncExecutor.execute(() -> {
            Session session = null;
            try {
                InterviewCallbacks callbacks = new InterviewCallbacks() {
                    /**
                     * 面试阶段变化时，把阶段名和提示文案推送给前端。
                     */
                    @Override
                    public void onStageChange(String stage, String message) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("stage_change").stage(stage).message(message).build());
                        // 实时保存阶段变化消息到 Redis，防止刷新丢失
                        saveMessageToRedis(ws.userID, sessionId, "system", message,
                                Map.of("message_type", "stage", "stage", stage));
                    }

                    /**
                     * 简历匹配结果回调，发送详细的匹配分析
                     */
                    @Override
                    public void onResumeMatch(ResumeMatchResult matchResult) {
                        String detailedMessage = formatResumeMatchResult(matchResult);

                        // 发送详细匹配结果
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("resume_match_result")
                                .content(detailedMessage)
                                .build());

                        // 同时发送一个简化版本作为阶段变更消息
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("stage_change")
                                .stage("resume_match_done")
                                .message(String.format("简历匹配完成，综合匹配度：%.0f%%", matchResult.getOverallScore()))
                                .build());

                        // 实时保存详细匹配结果到 Redis
                        saveMessageToRedis(ws.userID, sessionId, "system", detailedMessage,
                                Map.of("message_type", "resume_match_result", "overall_score", matchResult.getOverallScore()));
                    }

                    /**
                     * 面试官生成新问题时，把题号和题目内容推送给前端。
                     */
                    @Override
                    public void onQuestion(int questionNum, String content) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("question").questionNum(questionNum).content(content).build());
                        // 实时保存题目消息到 Redis，包含题号和消息类型元数据
                        saveMessageToRedis(ws.userID, sessionId, "assistant", content, Map.of("question_num", questionNum, "message_type", "question"));
                    }

                    /**
                     * 推送并保存低分题目巩固内容。该内容使用独立消息类型，避免被前端显示为第 0 道正式题。
                     */
                    @Override
                    public void onReviewItem(String content) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("review_item").content(content).build());
                        saveMessageToRedis(ws.userID, sessionId, "assistant", content,
                                Map.of("message_type", "review_item"));
                    }

                    /**
                     * 用户回答完成评分后，把分数、反馈和命中/遗漏要点推送给前端。
                     */
                    @Override
                    public void onScore(AnswerScore score) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("score")
                                .score(score.getScore())
                                .feedback(score.getFeedback())
                                .keyPointsHit(score.getKeyPointsHit())
                                .keyPointsMissed(score.getKeyPointsMissed())
                                .build());
                        // 实时保存评分消息到 Redis，包含分数、反馈和要点命中情况
                        saveMessageToRedis(ws.userID, sessionId, "system", score.getFeedback(), Map.of(
                                "score", score.getScore(),
                                "message_type", "score",
                                "key_points_hit", score.getKeyPointsHit(),
                                "key_points_missed", score.getKeyPointsMissed()));
                    }

                    /**
                     * 评估报告生成后，把报告 Markdown 内容推送给前端展示。
                     */
                    @Override
                    public void onReport(String report) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("report").content(report).build());
                        // 实时保存评估报告消息到 Redis
                        saveMessageToRedis(ws.userID, sessionId, "assistant", report, Map.of("message_type", "report"));
                    }

                    /**
                     * 复习计划生成后，把计划 Markdown 内容推送给前端展示。
                     */
                    @Override
                    public void onReviewPlan(String plan) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("review_plan").content(plan).build());
                        // 实时保存复习计划消息到 Redis
                        saveMessageToRedis(ws.userID, sessionId, "assistant", plan, Map.of("message_type", "review_plan"));
                    }

                    /**
                     * 从阻塞队列中等待用户回答，并把退出指令转换为面试终止异常。
                     */
                    @Override
                    public String getUserAnswer() throws InterruptedException, UserQuitException {
                        String answer = ws.answerCh.take();
                        if ("/quit".equals(answer) || "/exit".equals(answer)
                                || "退出".equals(answer) || "结束面试".equals(answer)) {
                            throw new UserQuitException();
                        }
                        // 用户回答也实时保存到 Redis，确保刷新后能恢复对话上下文
                        saveMessageToRedis(ws.userID, sessionId, "user", answer, Map.of("message_type", "text"));
                        return answer;
                    }
                };

                session = orchestrator.runInterview(finalJdText, finalResumeText, ws.userID, callbacks);
                // 将生成的 sessionId 设置到 Session 对象，作为数据库主键
                if (session != null) {
                    session.setId(sessionId);
                    session.setSessionType(Session.TYPE_INTERVIEW);
                    session.setCreatedAt(originalCreatedAt);
                    session.setPinned(originalPinned);
                    session.setPinnedAt(originalPinnedAt);
                    if (session.getTitle() == null || session.getTitle().isBlank()) {
                        session.setTitle(buildInterviewTitle(session));
                    }
                }
            } catch (Exception e) {
                log.error("[WS] 面试流程异常: {}", e.getMessage(), e);
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("error").message("面试流程异常: " + e.getMessage()).build());
            } finally {
                // 面试结束后：从 Redis 读取所有缓存消息，批量持久化到 MySQL，然后清除 Redis 缓存
                if (session != null) {
                    try {
                        List<ConversationMessage> cachedMessages = sessionCacheService.getMessages(ws.userID, sessionId);
                        List<ConversationMessage> allMessages = mergeMessages(preInterviewMessages, cachedMessages);
                        if (!allMessages.isEmpty()) {
                            session.setChatMessages(allMessages);
                            log.info("[WS] 合并 {} 条原聊天消息和 {} 条面试消息到会话",
                                    preInterviewMessages.size(), cachedMessages.size());
                        }
                        session = sessionRepository.save(session);
                        log.info("[WS] 会话已保存: sessionId={}, userId={}", session.getId(), ws.userID);
                        // 持久化成功后清除 Redis 缓存，释放内存
                        sessionCacheService.clearSessionCache(ws.userID, sessionId);
                    } catch (Exception e) {
                        log.warn("[WS] 保存会话失败: {}", e.getMessage());
                    } finally {
                        // 保留当前面试上下文，允许用户在面试结束后继续追问刚才的题目。
                        ws.chatSession = session;
                        ws.chatHistory = buildChatHistory(session.getChatMessages());
                    }
                }
                sendServerMsg(ws.conn, ServerMsg.builder().type("interview_complete").build());
                ws.interviewRunning = false;
                runningInterviews.remove(sessionId, ws);
            }
        });
    }

    /**
     * 用户回答
     */
    private void handleAnswer(WSSession ws, ClientMsg msg) {
        WSSession target = resolveRunningInterviewSession(ws, msg.getSessionId());
        if (target == null || !target.interviewRunning) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("当前没有进行中的面试").build());
            return;
        }
        target.answerCh.offer(msg.getContent() != null ? msg.getContent() : "");
    }

    /**
     * 上传题库
     */
    private void handleUploadQuestions(WSSession ws, ClientMsg msg) {
        String filename = msg.getFilename();
        String base64Data = msg.getData();

        if (filename == null || filename.isEmpty() || base64Data == null || base64Data.isEmpty()) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("文件名和数据不能为空").build());
            return;
        }

        asyncExecutor.execute(() -> {
            try {
                // SHA256 去重检查
                String hash = sha256(base64Data);
                String existingHash = redisStore.getFileHash(ws.userID, filename);
                if (hash.equals(existingHash)) {
                    sendServerMsg(ws.conn, ServerMsg.builder()
                            .type("upload_result")
                            .content("✅ 该题库之前已成功导入过（文件内容相同），本次自动跳过、无需重复上传，原有题目继续可用。")
                            .build());
                    return;
                }

                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("stage_change").stage("upload_parsing").message("正在解析文件内容...").build());

                // 解析文件
                String text = documentLoader.parseBase64File(filename, base64Data);

                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("stage_change").stage("upload_llm").message("正在用 LLM 提取题目...").build());

                // LLM 解析题目
                QuestionParser.ParseResult result = questionParser.parseQuestionBank(text);

                if (result.getQuestions().isEmpty()) {
                    sendServerMsg(ws.conn, ServerMsg.builder()
                            .type("upload_result")
                            .content(String.format("⚠️ 未能从该文件解析出有效题目（共识别 %d 道，均因内容过短等原因未通过校验）。请确认上传的是面试题库内容。", result.getTotal()))
                            .build());
                    return;
                }

                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("stage_change").stage("upload_indexing")
                        .message(String.format("正在写入知识库（%d 道题目）...", result.getQuestions().size()))
                        .build());

                // 先删除旧文件的题目
                milvusStore.deleteBySourceFile(ws.userID, filename);

                // 写入 Milvus
                List<MilvusStore.ParsedQuestionInput> milvusQuestions = result.getQuestions().stream()
                        .map(q -> MilvusStore.ParsedQuestionInput.builder()
                                .id(q.getId())
                                .content(q.getContent())
                                .reference(q.getReference())
                                .type(q.getType())
                                .difficulty(q.getDifficulty())
                                .skills(q.getSkills())
                                .build())
                        .toList();
                milvusStore.loadParsedQuestions(ws.userID, filename, milvusQuestions);

                // 写入 BM25
                List<RagDocument> bm25Docs = result.getQuestions().stream()
                        .map(q -> RagDocument.builder()
                                .id(q.getId())
                                .content(q.getContent() + "\n参考答案：" + q.getReference())
                                .build())
                        .toList();
                bm25Manager.appendDocuments(ws.userID, bm25Docs);

                // 保存文件 hash
                redisStore.saveFileHash(ws.userID, filename, hash);

                String resultMsg = String.format("✅ 题库导入成功！成功录入 %d 道题。", result.getSuccess());
                if (result.getFailed() > 0) {
                    resultMsg += String.format("\n（另有 %d 道因题目内容过短等原因被自动忽略，不影响其余题目的正常使用）", result.getFailed());
                }

                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("upload_result").content(resultMsg)
                        .message(formatParseErrors(result.getErrors())).build());
            } catch (Exception e) {
                log.error("[WS] 题库上传失败: {}", e.getMessage(), e);
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("error").message("题库上传失败: " + e.getMessage()).build());
            }
        });
    }

    /**
     * 用户主动终止面试
     */
    private void handleQuitInterview(WSSession ws, ClientMsg msg) {
        WSSession target = resolveRunningInterviewSession(ws, msg.getSessionId());
        if (target != null && target.interviewRunning) {
            target.answerCh.offer("/quit");
        } else {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("当前没有进行中的面试").build());
        }
    }

    /**
     * 开启一个新的普通聊天会话，清空当前聊天上下文和技能状态。
     */
    private void handleNewChat(WSSession ws) {
        ws.chatSession = null;
        ws.chatHistory.clear();
        ws.activeSkill = null;
        ws.skillState = null;
        notifySessionsChanged(ws);
    }

    /**
     * 向前端发送普通聊天回复，并把本轮用户输入和助手回复保存到历史会话。
     */
    private void sendChatReply(WSSession ws, String userInput, String reply) {
        sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content(reply).build());
        saveChatTurn(ws, userInput, reply);
    }

    /**
     * 将一轮普通聊天问答追加到当前聊天 Session 并持久化。首次创建聊天 Session 时，
     * 通过 session_started 把 ID 返回前端，供后续开始面试或 WebSocket 重连时复用。
     *
     * @param ws        当前 WebSocket 业务会话
     * @param userInput 本轮用户输入
     * @param reply     本轮助手回复
     */
    private void saveChatTurn(WSSession ws, String userInput, String reply) {
        try {
            boolean created = ws.chatSession == null;
            Session session = ensureChatSession(ws, userInput);
            LocalDateTime now = LocalDateTime.now();
            session.getChatMessages().add(ConversationMessage.builder()
                    .role("user")
                    .messageType("text")
                    .content(userInput)
                    .createdAt(now)
                    .build());
            session.getChatMessages().add(ConversationMessage.builder()
                    .role("assistant")
                    .messageType("text")
                    .content(reply)
                    .createdAt(now)
                    .build());
            session.setUpdatedAt(now);
            ws.chatSession = sessionRepository.save(session);
            if (created) {
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("session_started")
                        .content(ws.chatSession.getId())
                        .build());
            }
            notifySessionsChanged(ws);
        } catch (Exception e) {
            log.warn("[WS] 保存聊天历史失败: {}", e.getMessage());
        }
    }

    /**
     * 加载历史聊天会话到当前 WebSocket 状态，使后续聊天继续追加到该历史会话。
     */
    private void handleLoadSession(WSSession ws, ClientMsg msg) {
        String sessionId = msg.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("会话 ID 不能为空").build());
            return;
        }

        if (reattachRunningInterview(ws, sessionId)) {
            return;
        }

        try {
            Session session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null || !ws.userID.equals(session.getUserId())) {
                sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("无权访问该会话").build());
                return;
            }

            ws.activeSkill = null;
            ws.skillState = null;
            if (Session.TYPE_CHAT.equals(session.getSessionType()) || session.getChatMessages() != null) {
                if (session.getChatMessages() == null) {
                    session.setChatMessages(new ArrayList<>());
                }
                ws.chatSession = session;
                ws.chatHistory = buildChatHistory(session.getChatMessages());
            } else {
                ws.chatSession = null;
                ws.chatHistory.clear();
            }
        } catch (Exception e) {
            log.warn("[WS] 加载历史会话失败: {}", e.getMessage());
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("加载历史会话失败").build());
        }
    }

    private WSSession resolveRunningInterviewSession(WSSession ws, String sessionId) {
        if (ws.interviewRunning) {
            return ws;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        WSSession target = runningInterviews.get(sessionId);
        if (target == null || !ws.userID.equals(target.userID)) {
            return null;
        }
        WebSocketSession currentConn = ws.conn;
        target.conn = currentConn;
        sessions.put(currentConn.getId(), target);
        ws.answerCh = target.answerCh;
        ws.interviewRunning = target.interviewRunning;
        return target;
    }

    private boolean reattachRunningInterview(WSSession ws, String sessionId) {
        WSSession target = runningInterviews.get(sessionId);
        if (target == null || !ws.userID.equals(target.userID)) {
            return false;
        }
        WebSocketSession currentConn = ws.conn;
        target.conn = currentConn;
        sessions.put(currentConn.getId(), target);
        ws.answerCh = target.answerCh;
        ws.interviewRunning = target.interviewRunning;
        ws.activeSkill = null;
        ws.skillState = null;
        log.info("[WS] 用户 {} 重新挂接进行中的面试: sessionId={}", ws.userID, sessionId);
        return true;
    }

    /**
     * 获取当前可继续对话的 Session，包括已完成的面试 Session；不存在时才根据第一条输入
     * 创建新的普通聊天 Session。
     *
     * @param ws         当前 WebSocket 业务会话
     * @param firstInput 新建普通聊天时用于生成标题的第一条输入
     * @return 当前可追加消息的 Session
     */
    private Session ensureChatSession(WSSession ws, String firstInput) {
        if (ws.chatSession != null) {
            return ws.chatSession;
        }

        LocalDateTime now = LocalDateTime.now();
        Session session = Session.builder()
                .id(UUID.randomUUID().toString())
                .title(buildChatTitle(firstInput))
                .sessionType(Session.TYPE_CHAT)
                .userId(ws.userID)
                .status(Session.STATUS_CHAT)
                .chatMessages(new ArrayList<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
        ws.chatSession = session;
        return session;
    }

    /**
     * 将当前普通聊天升级为面试；没有可复用聊天时才创建新的面试 Session。
     *
     * @param currentChatSession 当前 WebSocket 关联的普通聊天 Session，可以为空
     * @param userId             当前登录用户 ID
     * @return 已升级的原 Session，或新创建的进行中面试 Session
     */
    static Session preparePendingInterviewSession(Session currentChatSession, String userId) {
        LocalDateTime now = LocalDateTime.now();
        if (currentChatSession != null
                && Session.TYPE_CHAT.equals(currentChatSession.getSessionType())
                && userId.equals(currentChatSession.getUserId())) {
            currentChatSession.setSessionType(Session.TYPE_INTERVIEW);
            currentChatSession.setStatus(Session.STATUS_INTERVIEWING);
            currentChatSession.setTitle("进行中的面试");
            currentChatSession.setUpdatedAt(now);
            if (currentChatSession.getChatMessages() == null) {
                currentChatSession.setChatMessages(new ArrayList<>());
            }
            return currentChatSession;
        }

        return Session.builder()
                .id(UUID.randomUUID().toString())
                .title("进行中的面试")
                .sessionType(Session.TYPE_INTERVIEW)
                .userId(userId)
                .status(Session.STATUS_INTERVIEWING)
                .chatMessages(new ArrayList<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 解析本次面试可以复用的普通聊天 Session。优先使用当前连接内存中的 Session；连接重建后，
     * 再按前端携带的 Session ID 查询数据库，并校验用户归属和会话类型。
     *
     * @param ws                 当前 WebSocket 业务会话
     * @param requestedSessionId 前端携带的当前 Session ID，可以为空
     * @return 可升级的普通聊天 Session；不存在、越权或类型不符时返回 null
     */
    private Session resolveChatSessionForInterview(WSSession ws, String requestedSessionId) {
        if (ws.chatSession != null
                && Session.TYPE_CHAT.equals(ws.chatSession.getSessionType())
                && ws.userID.equals(ws.chatSession.getUserId())) {
            return ws.chatSession;
        }
        if (requestedSessionId == null || requestedSessionId.isBlank()) {
            return null;
        }
        return sessionRepository.findById(requestedSessionId)
                .filter(session -> ws.userID.equals(session.getUserId()))
                .filter(session -> Session.TYPE_CHAT.equals(session.getSessionType()))
                .orElse(null);
    }

    /**
     * 按时间阶段顺序合并面试前聊天消息和面试过程消息，不修改任一输入列表，并去除
     * MySQL 与 Redis 中结构和创建时间完全相同的消息。
     *
     * @param first  面试前已经持久化的聊天消息
     * @param second 面试期间暂存在 Redis 的消息
     * @return 过滤 null 元素后的新消息列表
     */
    static List<ConversationMessage> mergeMessages(List<ConversationMessage> first,
                                                   List<ConversationMessage> second) {
        java.util.LinkedHashSet<ConversationMessage> merged = new java.util.LinkedHashSet<>(copyMessages(first));
        if (second != null) {
            second.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    /**
     * 从已持久化会话消息重建 ChatAgent 上下文。保留用户回答、助手问题/报告以及评分反馈，
     * 跳过阶段进度等系统噪声，并限制为最近 20 条以控制模型上下文长度。
     *
     * @param messages 当前 Session 的完整消息
     * @return 可直接传给 ChatAgent 的最近消息列表
     */
    static List<Message> buildChatHistory(List<ConversationMessage> messages) {
        List<Message> history = new ArrayList<>();
        if (messages != null) {
            for (ConversationMessage message : messages) {
                if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                    continue;
                }
                if ("user".equals(message.getRole())) {
                    history.add(new UserMessage(message.getContent()));
                } else if ("assistant".equals(message.getRole())
                        || ("system".equals(message.getRole()) && "score".equals(message.getMessageType()))) {
                    history.add(new AssistantMessage(message.getContent()));
                }
            }
        }
        int fromIndex = Math.max(0, history.size() - 20);
        return new ArrayList<>(history.subList(fromIndex, history.size()));
    }

    /**
     * 复制消息列表并过滤 null 元素，防止后续合并修改原集合。
     *
     * @param messages 待复制的消息列表，可以为空
     * @return 可变的新消息列表
     */
    private static List<ConversationMessage> copyMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(messages.stream().filter(java.util.Objects::nonNull).toList());
    }

    private String buildInterviewTitle(Session session) {
        if (session.getJdAnalysis() != null && session.getJdAnalysis().getPosition() != null
                && !session.getJdAnalysis().getPosition().isBlank()) {
            return session.getJdAnalysis().getPosition();
        }
        return "面试会话";
    }

    /**
     * 根据第一条用户输入生成历史会话标题，过长时截断。
     */
    private String buildChatTitle(String input) {
        String title = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) {
            return "新对话";
        }
        return title.length() <= 40 ? title : title.substring(0, 40) + "...";
    }

    /**
     * 裁剪普通聊天上下文，只保留最近的消息，避免请求模型时上下文无限增长。
     */
    private void trimChatHistory(WSSession ws) {
        int maxHistorySize = 20;
        if (ws.chatHistory.size() > maxHistorySize) {
            ws.chatHistory = new ArrayList<>(ws.chatHistory.subList(ws.chatHistory.size() - maxHistorySize, ws.chatHistory.size()));
        }
    }

    /**
     * 解析输入：处理文件上传、URL 和纯文本
     */
    private String resolveInput(String content) {
        if (content == null) content = "";

        // 处理 [FILE:filename]base64data 格式
        if (content.startsWith("[FILE:")) {
            int endBracket = content.indexOf(']');
            if (endBracket > 6) {
                String filename = content.substring(6, endBracket);
                String base64Data = content.substring(endBracket + 1);
                try {
                    return documentLoader.parseBase64File(filename, base64Data);
                } catch (Exception e) {
                    log.warn("[WS] 文件解析失败: {}", e.getMessage());
                    return content;
                }
            }
        }

        // URL 检测
        if (DocumentLoader.isURL(content)) {
            try {
                return webLoader.extractJDFromURL(content);
            } catch (Exception e) {
                log.warn("[WS] URL 抓取失败: {}", e.getMessage());
                return content;
            }
        }

        return content;
    }

    /**
     * 将简历匹配结果格式化为带分段和列表的 Markdown，保证前端展开后易于扫描。
     *
     * @param matchResult 简历与岗位的结构化匹配结果
     * @return 可直接展示和持久化的 Markdown 文本
     */
    private String formatResumeMatchResult(ResumeMatchResult matchResult) {
        StringBuilder content = new StringBuilder();
        content.append(String.format("**综合匹配度：%.0f%%**%n", matchResult.getOverallScore()));
        appendMarkdownSection(content, "候选人优势", matchResult.getStrengths());
        appendMarkdownSection(content, "待提升方面", matchResult.getWeaknesses());
        appendMarkdownSection(content, "面试重点考察方向", matchResult.getFocusAreas());
        appendMarkdownSection(content, "简历可深挖点", matchResult.getResumeGaps());
        return content.toString().trim();
    }

    /**
     * 向 Markdown 文本追加一个非空列表章节，过滤模型偶尔返回的空白条目。
     *
     * @param content Markdown 内容缓冲区
     * @param title   章节标题
     * @param items   章节列表项
     */
    private void appendMarkdownSection(StringBuilder content, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<String> validItems = items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
        if (validItems.isEmpty()) {
            return;
        }
        content.append("\n\n### ").append(title).append('\n');
        validItems.forEach(item -> content.append("- ").append(item).append('\n'));
    }

    /**
     * 将服务端消息序列化为 JSON 并发送到指定 WebSocket 连接。
     */
    private void sendServerMsg(WebSocketSession session, ServerMsg msg) {
        try {
            if (session.isOpen()) {
                String json = objectMapper.writeValueAsString(msg);
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.error("[WS] 发送消息失败: {}", e.getMessage());
        }
    }

    /**
     * 通知前端历史会话列表已变化，需要重新拉取列表。
     */
    private void notifySessionsChanged(WSSession ws) {
        sendServerMsg(ws.conn, ServerMsg.builder().type("sessions_changed").build());
    }

    /**
     * 格式化题库解析的校验失败详情，无错误返回 null（NON_NULL 不序列化）
     */
    private String formatParseErrors(List<QuestionParser.ParseError> errs) {
        if (errs == null || errs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (QuestionParser.ParseError e : errs) {
            sb.append(String.format("#%d: %s%n", e.getIndex(), e.getReason()));
        }
        return sb.toString();
    }

    /**
     * 计算输入内容的 SHA-256，用于判断上传题库文件内容是否重复。
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return input.hashCode() + "";
        }
    }

    /**
     * 保存消息到 Redis 缓存的辅助方法
     * <p>
     * 将消息内容、角色和元数据封装为 ConversationMessage 对象，然后调用 SessionCacheService 保存到 Redis。
     * 如果保存失败只记录警告日志，不影响主流程（面试仍可继续，只是刷新后可能丢失部分消息）。
     * </p>
     *
     * @param sessionId 会话唯一标识
     * @param role      消息角色：user/assistant/system
     * @param content   消息内容
     * @param metadata  元数据 Map，可包含 message_type、score、question_num 等额外信息
     */
    private void saveMessageToRedis(String userId, String sessionId, String role, String content, Map<String, Object> metadata) {
        try {
            ConversationMessage msg = ConversationMessage.builder()
                    .role(role)
                    .content(content)
                    .messageType(metadata.getOrDefault("message_type", "text").toString())
                    .metadata(new java.util.HashMap<>(metadata))
                    .createdAt(LocalDateTime.now())
                    .build();
            sessionCacheService.saveMessage(userId, sessionId, msg);
        } catch (Exception e) {
            log.warn("[WS] 保存消息到Redis失败: {}", e.getMessage());
        }
    }
}
