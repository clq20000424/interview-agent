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
import com.interview.agent.model.AnswerScore;
import com.interview.agent.model.ClientMsg;
import com.interview.agent.model.ServerMsg;
import com.interview.agent.rag.BM25Manager;
import com.interview.agent.rag.MilvusStore;
import com.interview.agent.rag.RagDocument;
import com.interview.agent.skill.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * WebSocket 处理器（与 Go 版本 ws_handler.go 完全一致的协议和逻辑）
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

    /**
     * session 管理
     */
    private final Map<String, WSSession> sessions = new ConcurrentHashMap<>();

    /**
     * 异步任务线程池（面试流程 / 题库上传），独立于 ForkJoinPool.commonPool。
     * 面试流程内部用 Spring AI Alibaba Graph 编排，graph 的 node_async 节点会提交到 commonPool 执行；
     * 若再用 commonPool 跑 runInterview 并阻塞等待节点（interview 节点还会阻塞等用户回答），
     * 会与节点执行互相抢占 commonPool 线程，导致线程饥饿 / 死锁。故面试走独立可扩展线程池。
     */
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "interview-async-worker");
        t.setDaemon(true);
        return t;
    });

    public WebSocketHandler(Orchestrator orchestrator, ChatAgent chatAgent,
                            IntentRouter intentRouter, SkillRegistry skillRegistry,
                            DocumentLoader documentLoader, QuestionParser questionParser,
                            WebLoader webLoader, MilvusStore milvusStore,
                            BM25Manager bm25Manager, RedisStore redisStore,
                            JwtService jwtService, ChatModel chatModel) {
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
        BlockingQueue<String> answerCh = new LinkedBlockingQueue<>();
        volatile boolean interviewRunning = false;
    }

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
            } catch (Exception e) {
                log.warn("[WS] token 验证失败: {}", e.getMessage());
            }
        }

        WSSession ws = new WSSession();
        ws.conn = session;
        ws.userID = userID;
        sessions.put(session.getId(), ws);

        log.info("[WS] 用户 {} 已连接 (sessionId={})", userID, session.getId());
        sendServerMsg(session, ServerMsg.builder().type("connected").content("连接成功").build());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("[WS] 连接关闭 (sessionId={})", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
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

            // 增强（Go 版本无）：技能会话进行中，若用户又发出明确的新技能/新测验意图
            // （例如测验做到一半再说「来几道 mysql 面试题」），则结束当前会话、切换到新技能，
            // 而不是把这句话当成当前题目的回答。普通的答题内容不含触发词，不会被误切。
            if (skillRegistry.match(input) == null) {
                SkillResponse resp = ws.activeSkill.handle(input, ws.skillState);
                ws.skillState = resp.getState();
                if (resp.isDone()) {
                    ws.activeSkill = null;
                    ws.skillState = null;
                }
                sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content(resp.getContent()).build());
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
            sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content(resp.getContent()).build());
            return;
        }

        // 优先级 3：ChatAgent 兜底
        String reply = chatAgent.chat(ws.chatHistory, input);
        sendServerMsg(ws.conn, ServerMsg.builder().type("chat_reply").content(reply).build());
    }

    /**
     * 开始面试
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

        // 解析 JD / 简历输入：支持 [FILE:] 文件、URL 抓取、纯文本（与 Go resolveInput 一致）
        jdText = resolveInput(jdText);
        resumeText = resolveInput(resumeText);

        ws.interviewRunning = true;
        ws.answerCh = new LinkedBlockingQueue<>();

        String finalJdText = jdText;
        String finalResumeText = resumeText;
        asyncExecutor.execute(() -> {
            try {
                InterviewCallbacks callbacks = new InterviewCallbacks() {
                    @Override
                    public void onStageChange(String stage, String message) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("stage_change").stage(stage).message(message).build());
                    }

                    @Override
                    public void onQuestion(int questionNum, String content) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("question").questionNum(questionNum).content(content).build());
                    }

                    @Override
                    public void onScore(AnswerScore score) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("score")
                                .score(score.getScore())
                                .feedback(score.getFeedback())
                                .keyPointsHit(score.getKeyPointsHit())
                                .keyPointsMissed(score.getKeyPointsMissed())
                                .build());
                    }

                    @Override
                    public void onReport(String report) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("report").content(report).build());
                    }

                    @Override
                    public void onReviewPlan(String plan) {
                        sendServerMsg(ws.conn, ServerMsg.builder()
                                .type("review_plan").content(plan).build());
                    }

                    @Override
                    public String getUserAnswer() throws InterruptedException, UserQuitException {
                        String answer = ws.answerCh.take();
                        if ("/quit".equals(answer) || "/exit".equals(answer)
                                || "退出".equals(answer) || "结束面试".equals(answer)) {
                            throw new UserQuitException();
                        }
                        return answer;
                    }
                };

                orchestrator.runInterview(finalJdText, finalResumeText, ws.userID, callbacks);
            } catch (Exception e) {
                log.error("[WS] 面试流程异常: {}", e.getMessage(), e);
                sendServerMsg(ws.conn, ServerMsg.builder()
                        .type("error").message("面试流程异常: " + e.getMessage()).build());
            } finally {
                // 与 Go 版本一致：面试流程结束后（无论正常完成、用户终止或异常）都通知前端
                sendServerMsg(ws.conn, ServerMsg.builder().type("interview_complete").build());
                ws.interviewRunning = false;
            }
        });
    }

    /**
     * 用户回答
     */
    private void handleAnswer(WSSession ws, ClientMsg msg) {
        if (!ws.interviewRunning) {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("当前没有进行中的面试").build());
            return;
        }
        ws.answerCh.offer(msg.getContent() != null ? msg.getContent() : "");
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
        if (ws.interviewRunning) {
            ws.answerCh.offer("/quit");
        } else {
            sendServerMsg(ws.conn, ServerMsg.builder().type("error").message("当前没有进行中的面试").build());
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
     * 格式化题库解析的校验失败详情（与 Go formatParseErrors 一致），无错误返回 null（NON_NULL 不序列化）
     */
    private String formatParseErrors(List<QuestionParser.ParseError> errs) {
        if (errs == null || errs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (QuestionParser.ParseError e : errs) {
            sb.append(String.format("#%d: %s%n", e.getIndex(), e.getReason()));
        }
        return sb.toString();
    }

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
}
