package com.interview.agent.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interview.agent.model.ConversationMessage;
import com.interview.agent.model.EvaluationReport;
import com.interview.agent.model.JDAnalysis;
import com.interview.agent.model.Session;
import com.interview.agent.repository.SessionRepository;
import com.interview.agent.service.SessionCacheService;
import com.interview.agent.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话管理 Controller
 * - GET /api/sessions: 获取当前用户的所有会话列表
 * - GET /api/sessions/{id}: 获取指定会话详情
 * - PATCH /api/sessions/{id}/title: 修改会话标题
 * - PATCH /api/sessions/{id}/pin: 修改会话置顶状态
 * - DELETE /api/sessions/{id}: 删除指定会话
 *
 * @author 陈龙强
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionRepository sessionRepository;
    private final SessionCacheService sessionCacheService;
    private final SessionService sessionService;

    /**
     * 获取当前用户的所有会话列表
     */
    @GetMapping
    public ResponseEntity<List<SessionSummary>> getSessions(@RequestAttribute("username") String username) {
        try {
            List<Session> sessions = sessionRepository.findByUserIdOrderByPinnedAndUpdatedDesc(username);
            return ResponseEntity.ok(sessions.stream().map(SessionController::toSummary).toList());
        } catch (Exception e) {
            log.error("[Session] 获取会话列表失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取指定会话详情。若会话仍有 Redis 实时消息，则与 MySQL 已持久化消息合并后返回，
     * 保证页面内切换会话和刷新页面使用同一份完整数据。
     *
     * @param id       会话 ID
     * @param username 当前登录用户名
     * @return 当前用户可访问的完整会话详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id, @RequestAttribute("username") String username) {
        try {
            Session session = sessionRepository.findById(id).orElse(null);
            if (session == null) {
                return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
            }

            // 验证会话属于当前用户
            if (!session.getUserId().equals(username)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权访问该会话"));
            }

            List<ConversationMessage> cachedMessages = sessionCacheService.getMessages(username, id);
            if (!cachedMessages.isEmpty()) {
                session.setChatMessages(mergeMessages(session.getChatMessages(), cachedMessages));
            }
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            log.error("[Session] 获取会话详情失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "获取会话详情失败"));
        }
    }

    /**
     * 修改指定会话的标题
     */
    @PatchMapping("/{id}/title")
    public ResponseEntity<?> renameSession(@PathVariable String id,
                                           @RequestBody RenameSessionRequest request,
                                           @RequestAttribute("username") String username) {
        try {
            Session session = getOwnedSession(id, username);
            if (session == null) {
                return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
            }

            String title = request.title() == null ? "" : request.title().trim();
            if (title.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "会话名称不能为空"));
            }
            if (title.length() > 200) {
                return ResponseEntity.badRequest().body(Map.of("error", "会话名称不能超过 200 个字符"));
            }

            session.setTitle(title);
            Session saved = sessionRepository.save(session);
            log.info("[Session] 用户 {} 修改会话名称: sessionId={}", username, id);
            return ResponseEntity.ok(toSummary(saved));
        } catch (IllegalAccessException e) {
            return ResponseEntity.status(403).body(Map.of("error", "无权修改该会话"));
        } catch (Exception e) {
            log.error("[Session] 修改会话名称失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "修改会话名称失败"));
        }
    }

    /**
     * 修改指定会话的置顶状态
     */
    @PatchMapping("/{id}/pin")
    public ResponseEntity<?> updatePinned(@PathVariable String id,
                                          @RequestBody PinSessionRequest request,
                                          @RequestAttribute("username") String username) {
        try {
            Session session = getOwnedSession(id, username);
            if (session == null) {
                return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
            }

            boolean pinned = Boolean.TRUE.equals(request.pinned());
            session.setPinned(pinned);
            session.setPinnedAt(pinned ? LocalDateTime.now() : null);
            Session saved = sessionRepository.save(session);
            log.info("[Session] 用户 {} {}会话: sessionId={}", username, pinned ? "置顶" : "取消置顶", id);
            return ResponseEntity.ok(toSummary(saved));
        } catch (IllegalAccessException e) {
            return ResponseEntity.status(403).body(Map.of("error", "无权修改该会话"));
        } catch (Exception e) {
            log.error("[Session] 修改会话置顶状态失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "修改会话置顶状态失败"));
        }
    }

    /**
     * 删除指定会话及其在 MySQL、Redis、长短期记忆中的关联数据。进行中的面试会继续写入，
     * 为避免删除后被异步任务重新创建，必须先终止面试再删除。
     *
     * @param id 待删除的 Session ID
     * @param username 当前登录用户名
     * @return 删除成功返回 204；越权、会话不存在或仍在运行时返回对应错误
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable String id, @RequestAttribute("username") String username) {
        try {
            Session session = sessionRepository.findById(id).orElse(null);
            if (session == null) {
                return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
            }

            // 验证会话属于当前用户
            if (!session.getUserId().equals(username)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权删除该会话"));
            }

            if (Session.STATUS_INTERVIEWING.equals(session.getStatus())) {
                return ResponseEntity.status(409).body(Map.of("error", "面试正在进行中，请先终止面试再删除"));
            }

            sessionService.delete(session);
            log.info("[Session] 用户 {} 删除了会话及关联数据 {}", username, id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("[Session] 删除会话失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "删除会话失败"));
        }
    }

    /**
     * 检查是否有进行中的面试会话（从 Redis 缓存中）
     * <p>
     * 用于页面刷新时恢复会话。前端在 App.tsx 的 useEffect 中调用此接口，
     * 如果返回 has_cached=true，则自动恢复缓存的消息并提示用户继续面试。
     * </p>
     * <p>
     * 响应格式：
     * - 有缓存：{ "has_cached": true, "session": {...}, "cached_messages": [...] }
     * - 无缓存：{ "has_cached": false }
     * </p>
     *
     * @param username 当前登录用户名（从 JWT 解析后注入）
     * @return 活跃会话信息及缓存消息
     */
    @GetMapping("/active")
    public ResponseEntity<?> getActiveSession(@RequestAttribute("username") String username) {
        try {
            // 查找状态为 interviewing 的会话（即正在进行中的面试）
            List<Session> sessions = sessionRepository.findByUserIdAndStatus(username, "interviewing");
            if (!sessions.isEmpty()) {
                Session activeSession = sessions.getFirst();
                // 检查 Redis 中是否有该会话的缓存消息
                boolean hasCachedMessages = sessionCacheService.hasCachedMessages(username, activeSession.getId());
                if (hasCachedMessages) {
                    // 从 Redis 读取所有缓存消息，与会话信息一起返回给前端
                    return ResponseEntity.ok(buildCachedSessionResponse(username, activeSession, true));
                }
            }

            // 兜底：如果进行中会话索引还没写入 MySQL，但 Redis 已经有缓存，也允许从 Redis 恢复页面。
            List<String> cachedSessionIds = sessionCacheService.getCachedSessionIds(username);
            if (!cachedSessionIds.isEmpty()) {
                String sessionId = cachedSessionIds.getFirst();
                Session cachedSession = sessionRepository.findById(sessionId)
                        .filter(session -> username.equals(session.getUserId()))
                        .orElseGet(() -> buildCachedOnlySession(username, sessionId));
                return ResponseEntity.ok(buildCachedSessionResponse(username, cachedSession, false));
            }
            // 没有进行中的面试或没有缓存消息
            return ResponseEntity.ok(Map.of("has_cached", false));
        } catch (Exception e) {
            log.error("[Session] 检查活跃会话失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "检查活跃会话失败"));
        }
    }

    /**
     * 构造进行中面试的恢复响应。普通聊天升级为面试后，面试前消息已经保存在 MySQL，
     * 面试过程消息暂存在 Redis，因此恢复页面时需要按阶段顺序合并两部分消息。
     *
     * @param username           当前登录用户名，用于隔离 Redis 会话数据
     * @param session            MySQL 中的进行中 Session，或根据缓存构造的临时 Session
     * @param mysqlSessionExists 是否已找到真实的 MySQL Session 索引
     * @return 包含会话摘要、完整恢复消息和缓存状态的响应 Map
     */
    private Map<String, Object> buildCachedSessionResponse(String username, Session session, boolean mysqlSessionExists) {
        List<ConversationMessage> cachedMessages = sessionCacheService.getMessages(username, session.getId());
        List<ConversationMessage> allMessages = mergeMessages(session.getChatMessages(), cachedMessages);
        return Map.of(
                "session", toSummary(session),
                "cached_messages", allMessages,
                "has_cached", true,
                "cache_message_count", allMessages.size(),
                "mysql_session_exists", mysqlSessionExists,
                "persisted_to_mysql", false
        );
    }

    /**
     * 按持久化消息、实时缓存消息的顺序合并会话内容，不修改输入列表并过滤空元素和完整重复项。
     * ConversationMessage 的相等判断包含角色、类型、内容、元数据和创建时间，因此不同时间发送的
     * 相同文本仍会保留，只有 MySQL 与 Redis 中完全相同的消息会被合并。
     *
     * @param persistedMessages MySQL 中已经持久化的消息
     * @param cachedMessages    Redis 中尚未批量落库的实时消息
     * @return 可直接返回前端的完整消息列表
     */
    static List<ConversationMessage> mergeMessages(List<ConversationMessage> persistedMessages,
                                                    List<ConversationMessage> cachedMessages) {
        java.util.LinkedHashSet<ConversationMessage> merged = new java.util.LinkedHashSet<>();
        if (persistedMessages != null) {
            persistedMessages.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        if (cachedMessages != null) {
            cachedMessages.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        return new java.util.ArrayList<>(merged);
    }

    private Session buildCachedOnlySession(String username, String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        return Session.builder()
                .id(sessionId)
                .title("进行中的面试")
                .sessionType(Session.TYPE_INTERVIEW)
                .userId(username)
                .status(Session.STATUS_INTERVIEWING)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Session getOwnedSession(String id, String username) throws IllegalAccessException {
        Session session = sessionRepository.findById(id).orElse(null);
        if (session == null) {
            return null;
        }
        if (!session.getUserId().equals(username)) {
            throw new IllegalAccessException();
        }
        return session;
    }

    private static SessionSummary toSummary(Session session) {
        return new SessionSummary(
                session.getId(),
                session.getTitle(),
                session.getSessionType(),
                session.getUserId(),
                session.getStatus(),
                Boolean.TRUE.equals(session.getPinned()),
                session.getPinnedAt(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                toJdSummary(session.getJdAnalysis()),
                toReportSummary(session.getReport())
        );
    }

    private static JdAnalysisSummary toJdSummary(JDAnalysis jdAnalysis) {
        if (jdAnalysis == null) {
            return null;
        }
        return new JdAnalysisSummary(jdAnalysis.getPosition(), jdAnalysis.getExperienceLevel());
    }

    private static ReportSummary toReportSummary(EvaluationReport report) {
        if (report == null) {
            return null;
        }
        return new ReportSummary(report.getOverallScore());
    }

    public record SessionSummary(
            String id,
            String title,
            @JsonProperty("session_type") String sessionType,
            @JsonProperty("user_id") String userId,
            String status,
            Boolean pinned,
            @JsonProperty("pinned_at") LocalDateTime pinnedAt,
            @JsonProperty("created_at") LocalDateTime createdAt,
            @JsonProperty("updated_at") LocalDateTime updatedAt,
            @JsonProperty("jd_analysis") JdAnalysisSummary jdAnalysis,
            ReportSummary report
    ) {
    }

    public record JdAnalysisSummary(
            String position,
            @JsonProperty("experience_level") String experienceLevel
    ) {
    }

    public record ReportSummary(
            @JsonProperty("overall_score") double overallScore
    ) {
    }

    public record RenameSessionRequest(String title) {
    }

    public record PinSessionRequest(Boolean pinned) {
    }
}
