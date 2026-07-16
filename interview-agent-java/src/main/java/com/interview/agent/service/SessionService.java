package com.interview.agent.service;

import com.interview.agent.memory.LongTermMemory;
import com.interview.agent.memory.MySQLStore;
import com.interview.agent.memory.RedisStore;
import com.interview.agent.memory.ShortTermMemory;
import com.interview.agent.model.Session;
import com.interview.agent.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话服务
 *
 * @author 陈龙强
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final SessionCacheService sessionCacheService;
    private final MySQLStore mysqlStore;
    private final RedisStore redisStore;
    private final LongTermMemory longTermMemory;
    private final ShortTermMemory shortTermMemory;

    /**
     * 删除会话主记录、独立面试记录、长短期历史以及 Redis 消息和状态缓存。
     * MySQL 操作处于同一事务；Redis 清理失败会抛出异常并使数据库事务回滚，允许用户重试。
     *
     * @param session 已完成所有权校验且不在运行中的会话
     */
    @Transactional
    public void delete(Session session) {
        String userId = session.getUserId();
        String sessionId = session.getId();

        sessionRepository.delete(session);
        sessionRepository.flush();
        int deletedRecords = mysqlStore.deleteInterviewRecord(userId, sessionId);
        boolean removedFromHistory = longTermMemory.removeInterviewRecord(userId, sessionId);
        shortTermMemory.clear(sessionId);
        sessionCacheService.clearSessionCache(userId, sessionId);
        redisStore.deleteSession(sessionId);

        log.info("[SessionService] 会话清理完成: userId={}, sessionId={}, interviewRecords={}, longTermHistory={}",
                userId, sessionId, deletedRecords, removedFromHistory);
    }
}
