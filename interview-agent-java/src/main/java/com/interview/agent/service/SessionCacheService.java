package com.interview.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.model.ConversationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * 会话缓存服务 - 使用 Redis 缓存实时消息，异步持久化到 MySQL
 * <p>
 * 设计目的：解决面试过程中刷新页面或切换会话导致消息丢失的问题。
 * 采用 Redis 作为高速缓存层，面试过程中每条消息实时写入用户维度的 Redis Hash，
 * 面试结束后批量从 Redis 读取并持久化到 MySQL，最后清除 Redis 缓存。
 * </p>
 * <p>
 * Redis Key 设计：
 * - 消息 Hash：session:messages:{userId}，field 为 sessionId，value 为消息列表 JSON
 * - 状态 Hash：session:state:{userId}，field 为 sessionId，value 为状态 JSON
 * </p>
 *
 * @author 陈龙强
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCacheService {

    /**
     * Redis 操作模板，用于执行 Redis 命令
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * JSON 序列化/反序列化工具，注册了 JavaTimeModule 以支持 LocalDateTime 等时间类型
     */
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * Redis key 前缀：所有会话相关 key 都以 "session:" 开头，便于管理和清理
     */
    private static final String SESSION_KEY_PREFIX = "session:";

    /**
     * 消息 Hash key 前缀：拼接后形成 session:messages:{userId}
     */
    private static final String MESSAGES_HASH_PREFIX = SESSION_KEY_PREFIX + "messages:";

    /**
     * 会话状态 Hash key 前缀：拼接后形成 session:state:{userId}
     */
    private static final String STATE_HASH_PREFIX = SESSION_KEY_PREFIX + "state:";

    /**
     * 缓存过期时间：24小时。超过此时间未完成的面试会自动清理，避免 Redis 内存无限增长
     */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    /**
     * 保存单条消息到 Redis 缓存
     * <p>
     * 使用 Redis Hash 按 userId 分桶，field 为 sessionId，value 为消息列表 JSON。
     * 每次写入后重置 TTL，确保活跃会话不会过期。
     * </p>
     *
     * @param userId    用户唯一标识
     * @param sessionId 会话唯一标识
     * @param message   要缓存的消息对象
     */
    public void saveMessage(String userId, String sessionId, ConversationMessage message) {
        String key = messagesHashKey(userId);
        try {
            List<ConversationMessage> messages = new ArrayList<>(readMessagesFromHash(key, sessionId));
            messages.add(message);
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForHash().put(key, sessionId, json);
            redisTemplate.expire(key, CACHE_TTL);
            log.debug("[SessionCache] 保存消息到Redis: userId={}, sessionId={}, role={}", userId, sessionId, message.getRole());
        } catch (Exception e) {
            log.error("[SessionCache] 保存消息失败: userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    /**
     * 从 Redis 获取指定会话的所有缓存消息
     * <p>
     * 从用户 Hash 中读取 sessionId 对应的消息列表 JSON，然后反序列化为 ConversationMessage 对象。
     * </p>
     *
     * @param userId    用户唯一标识
     * @param sessionId 会话唯一标识
     * @return 消息列表，如果没有缓存或发生错误则返回空列表
     */
    public List<ConversationMessage> getMessages(String userId, String sessionId) {
        String key = messagesHashKey(userId);
        try {
            return readMessagesFromHash(key, sessionId);
        } catch (Exception e) {
            log.error("[SessionCache] 获取消息失败: userId={}, sessionId={}", userId, sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取指定会话的缓存消息数量
     * <p>
     * 从用户 Hash 中读取 sessionId 对应的消息列表后返回列表长度。
     * </p>
     *
     * @param userId    用户唯一标识
     * @param sessionId 会话唯一标识
     * @return 消息数量，如果 key 不存在则返回 0
     */
    public long getMessageCount(String userId, String sessionId) {
        return getMessages(userId, sessionId).size();
    }

    /**
     * 清除指定会话的缓存消息
     * <p>
     * 在面试结束并成功持久化到 MySQL 后调用，释放 Redis 内存。
     * </p>
     *
     * @param userId    用户唯一标识
     * @param sessionId 会话唯一标识
     */
    public void clearMessages(String userId, String sessionId) {
        String key = messagesHashKey(userId);
        redisTemplate.opsForHash().delete(key, sessionId);
        log.info("[SessionCache] 清除Redis缓存: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * 检查指定会话是否有缓存的消息
     * <p>
     * 通过消息数量是否大于 0 来判断，用于前端检查是否有未完成的面试。
     * </p>
     *
     * @param userId    用户唯一标识
     * @param sessionId 会话唯一标识
     * @return true 表示有缓存消息，false 表示没有
     */
    public boolean hasCachedMessages(String userId, String sessionId) {
        return getMessageCount(userId, sessionId) > 0;
    }

    /**
     * 获取指定用户在 Redis 中仍有缓存消息的会话 ID。
     * <p>
     * 用于页面刷新恢复时兜底：即使 MySQL 中还没有进行中会话记录，也可以从用户 Hash 的 field 找回 sessionId。
     * </p>
     *
     * @param userId 用户唯一标识
     * @return Redis 中有缓存消息的 sessionId 列表
     */
    public List<String> getCachedSessionIds(String userId) {
        String key = messagesHashKey(userId);
        try {
            Set<Object> keys = redisTemplate.opsForHash().keys(key);
            if (keys.isEmpty()) {
                return Collections.emptyList();
            }
            return keys.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(sessionId -> !sessionId.isBlank())
                    .toList();
        } catch (Exception e) {
            log.error("[SessionCache] 获取用户缓存会话失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 保存会话状态到 Redis（用于断点续传）
     * <p>
     * 使用用户维度的 Redis Hash 存储 JSON 格式的会话状态，如当前阶段、已答题数等。
     * 可用于恢复面试进度，实现断点续传功能。
     * </p>
     *
     * @param userId    用户唯一标识
     * @param sessionId 会话唯一标识
     * @param state     状态对象，会被序列化为 JSON 字符串
     */
    public void saveSessionState(String userId, String sessionId, Object state) {
        String key = stateHashKey(userId);
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForHash().put(key, sessionId, json);
            redisTemplate.expire(key, CACHE_TTL);
            log.debug("[SessionCache] 保存会话状态: userId={}, sessionId={}", userId, sessionId);
        } catch (JsonProcessingException e) {
            log.error("[SessionCache] 序列化状态失败: userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    /**
     * 获取会话状态
     * <p>
     * 从 Redis 读取 JSON 字符串并反序列化为指定类型的对象。
     * </p>
     *
     * @param userId    用户唯一标识
     * @param sessionId 会话唯一标识
     * @param clazz     目标类型 Class 对象
     * @param <T>       目标类型泛型
     * @return 状态对象，如果不存在或反序列化失败则返回 null
     */
    public <T> T getSessionState(String userId, String sessionId, Class<T> clazz) {
        String key = stateHashKey(userId);
        try {
            Object value = redisTemplate.opsForHash().get(key, sessionId);
            String json = value instanceof String ? (String) value : null;
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("[SessionCache] 反序列化状态失败: userId={}, sessionId={}", userId, sessionId, e);
            return null;
        }
    }

    /**
     * 清除会话的所有缓存（包括消息和状态）
     * <p>
     * 同时删除 messages 和 state 两个 key，彻底清理会话缓存。
     * 通常在面试完成或用户主动放弃时调用。
     * </p>
     *
     * @param userId    用户唯一标识
     * @param sessionId 会话唯一标识
     */
    public void clearSessionCache(String userId, String sessionId) {
        redisTemplate.opsForHash().delete(messagesHashKey(userId), sessionId);
        redisTemplate.opsForHash().delete(stateHashKey(userId), sessionId);
        log.info("[SessionCache] 清除会话所有缓存: userId={}, sessionId={}", userId, sessionId);
    }

    private String messagesHashKey(String userId) {
        return MESSAGES_HASH_PREFIX + requireText(userId, "userId");
    }

    private String stateHashKey(String userId) {
        return STATE_HASH_PREFIX + requireText(userId, "userId");
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private List<ConversationMessage> readMessagesFromHash(String key, String sessionId) throws JsonProcessingException {
        Object value = redisTemplate.opsForHash().get(key, requireText(sessionId, "sessionId"));
        String json = value instanceof String ? (String) value : null;
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        return objectMapper.readValue(json, new TypeReference<List<ConversationMessage>>() {
                }).stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
