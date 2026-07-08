package com.interview.agent.repository;

import com.interview.agent.model.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话 Repository
 *
 * @author 陈龙强
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, String> {

    /**
     * 查询用户的所有会话：置顶会话优先，其余按最近更新时间倒序。
     */
    @Query("""
            SELECT s FROM Session s
            WHERE s.userId = :userId
            ORDER BY
              CASE WHEN s.pinned = true THEN 0 ELSE 1 END,
              s.pinnedAt DESC,
              s.updatedAt DESC
            """)
    List<Session> findByUserIdOrderByPinnedAndUpdatedDesc(String userId);

    /**
     * 查询用户的活跃会话（未完成）
     */
    @Query("SELECT s FROM Session s WHERE s.userId = :userId AND s.status NOT IN ('completed', 'terminated') ORDER BY s.updatedAt DESC")
    List<Session> findActiveSessionsByUserId(String userId);

    /**
     * 根据用户ID和状态查询会话
     */
    List<Session> findByUserIdAndStatus(String userId, String status);
}
