package com.interview.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * MySQL 存储层
 * 使用 JPA EntityManager 执行原生 SQL。
 *
 * @author 陈龙强
 */
@Slf4j
@Component
public class MySQLStore {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * 初始化建表。
     * Spring Data JPA 的 ddl-auto 只处理 @Entity 的 users 表；user_profiles / interview_records
     * 是用原生 SQL 操作的非实体表，必须在此显式建表。
     * 用 JdbcTemplate 执行 DDL（自带连接、自动提交），避免 @PostConstruct 上 @Transactional
     * 自调用代理不生效导致的 TransactionRequired 问题。
     */
    @PostConstruct
    public void migrate() {
        try {
            jdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS user_profiles (
                            user_id VARCHAR(128) PRIMARY KEY,
                            name VARCHAR(256) DEFAULT '',
                            skill_level JSON,
                            weak_points JSON,
                            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            jdbcTemplate.execute("""
                        CREATE TABLE IF NOT EXISTS interview_records (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            user_id VARCHAR(128) NOT NULL,
                            session_id VARCHAR(128) NOT NULL UNIQUE,
                            position VARCHAR(256) DEFAULT '',
                            overall_score DOUBLE DEFAULT 0,
                            report_json MEDIUMTEXT,
                            review_plan_json MEDIUMTEXT,
                            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                            INDEX idx_user_id (user_id),
                            INDEX idx_session_id (session_id)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);

            log.info("[MySQLStore] 表结构就绪");
        } catch (Exception e) {
            log.warn("[MySQLStore] 建表异常（可能已存在）: {}", e.getMessage());
        }
    }

    @Transactional
    public void saveProfile(UserProfile profile) {
        try {
            String skillLevelJson = objectMapper.writeValueAsString(profile.getSkillLevel());
            String weakPointsJson = objectMapper.writeValueAsString(profile.getWeakPoints());

            entityManager.createNativeQuery("""
                                INSERT INTO user_profiles (user_id, name, skill_level, weak_points)
                                VALUES (:userId, :name, :skillLevel, :weakPoints)
                                ON DUPLICATE KEY UPDATE
                                    name = VALUES(name),
                                    skill_level = VALUES(skill_level),
                                    weak_points = VALUES(weak_points),
                                    updated_at = NOW()
                            """)
                    .setParameter("userId", profile.getUserId())
                    .setParameter("name", profile.getName() != null ? profile.getName() : "")
                    .setParameter("skillLevel", skillLevelJson)
                    .setParameter("weakPoints", weakPointsJson)
                    .executeUpdate();
        } catch (Exception e) {
            log.error("[MySQLStore] 保存 Profile 失败: {}", e.getMessage());
        }
    }

    public UserProfile loadProfile(String userId) {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> results = entityManager.createNativeQuery(
                    "SELECT user_id, name, skill_level, weak_points FROM user_profiles WHERE user_id = :userId"
            ).setParameter("userId", userId).getResultList();

            if (results.isEmpty()) return null;

            Object[] row = results.get(0);
            UserProfile profile = new UserProfile();
            profile.setUserId((String) row[0]);
            profile.setName((String) row[1]);

            if (row[2] != null) {
                profile.setSkillLevel(objectMapper.readValue(row[2].toString(),
                        new TypeReference<Map<String, String>>() {
                        }));
            }
            if (row[3] != null) {
                profile.setWeakPoints(objectMapper.readValue(row[3].toString(),
                        new TypeReference<List<UserProfile.WeakPoint>>() {
                        }));
            }

            return profile;
        } catch (Exception e) {
            log.error("[MySQLStore] 加载 Profile 失败: {}", e.getMessage());
            return null;
        }
    }

    @Transactional
    public void saveInterviewRecord(String userId, UserProfile.InterviewRecord record,
                                    String reportJson, String reviewPlanJson) {
        try {
            entityManager.createNativeQuery("""
                                INSERT INTO interview_records (user_id, session_id, position, overall_score, report_json, review_plan_json)
                                VALUES (:userId, :sessionId, :position, :score, :report, :reviewPlan)
                            """)
                    .setParameter("userId", userId)
                    .setParameter("sessionId", record.getSessionId())
                    .setParameter("position", record.getPosition())
                    .setParameter("score", record.getOverallScore())
                    .setParameter("report", reportJson)
                    .setParameter("reviewPlan", reviewPlanJson)
                    .executeUpdate();
        } catch (Exception e) {
            log.error("[MySQLStore] 保存面试记录失败: {}", e.getMessage());
        }
    }
}
