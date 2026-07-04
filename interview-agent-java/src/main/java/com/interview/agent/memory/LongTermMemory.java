package com.interview.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 长期记忆：管理用户画像和面试历史
 * - 薄弱点超过 30 天自动淘汰
 * - 出题时只取最弱的 Top 10
 * - 得分 < 60 记录为薄弱点，得分 >= 80 移除薄弱点
 *
 * @author 陈龙强
 */
@Slf4j
@Component
public class LongTermMemory {

    /** 薄弱点超过 30 天自动淘汰 */
    private static final Duration WEAK_POINT_MAX_AGE = Duration.ofDays(30);

    /** 出题时只取最弱的 Top N */
    private static final int WEAK_POINT_TOP_N = 10;

    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();
    private final CombinedStore store;

    public LongTermMemory(CombinedStore store) {
        this.store = store;
    }

    /**
     * 获取用户画像
     */
    public UserProfile getProfile(String userID) {
        // 先查内存缓存
        UserProfile profile = profiles.get(userID);
        if (profile != null) {
            return profile;
        }

        // 尝试从持久化存储加载
        if (store != null) {
            profile = store.loadProfile(userID);
            if (profile != null) {
                profiles.put(userID, profile);
                return profile;
            }
        }

        // 创建新用户画像
        profile = UserProfile.builder()
                .userId(userID)
                .skillLevel(new HashMap<>())
                .weakPoints(new ArrayList<>())
                .interviewHist(new ArrayList<>())
                .updatedAt(LocalDateTime.now())
                .build();
        profiles.put(userID, profile);
        return profile;
    }

    /**
     * 更新薄弱点
     * - 得分 < 60：新增或更新薄弱点（wrongCount++）
     * - 得分 >= 80：说明已掌握，移除薄弱点
     */
    public void updateWeakPoints(String userID, String topic, double score) {
        UserProfile profile = profiles.computeIfAbsent(userID, k ->
                UserProfile.builder()
                        .userId(k)
                        .skillLevel(new HashMap<>())
                        .weakPoints(new ArrayList<>())
                        .interviewHist(new ArrayList<>())
                        .build());

        synchronized (profile) {
            List<UserProfile.WeakPoint> weakPoints = profile.getWeakPoints();
            if (weakPoints == null) {
                weakPoints = new ArrayList<>();
                profile.setWeakPoints(weakPoints);
            }

            boolean found = false;
            Iterator<UserProfile.WeakPoint> it = weakPoints.iterator();
            while (it.hasNext()) {
                UserProfile.WeakPoint wp = it.next();
                if (wp.getTopic().equals(topic)) {
                    wp.setScore(score);
                    wp.setHitCount(wp.getHitCount() + 1);
                    if (score < 60) {
                        wp.setWrongCount(wp.getWrongCount() + 1);
                    }
                    wp.setLastSeen(LocalDateTime.now());
                    found = true;

                    // 得分 >= 80 说明已掌握，移除薄弱点
                    if (score >= 80) {
                        it.remove();
                    }
                    break;
                }
            }

            // 得分 < 60 才记录为薄弱点
            if (!found && score < 60) {
                weakPoints.add(UserProfile.WeakPoint.builder()
                        .topic(topic)
                        .score(score)
                        .hitCount(1)
                        .wrongCount(1)
                        .lastSeen(LocalDateTime.now())
                        .build());
            }

            profile.setUpdatedAt(LocalDateTime.now());
        }

        // 持久化
        if (store != null) {
            store.saveProfile(profile);
        }
    }

    /**
     * 添加面试记录
     */
    public void addInterviewRecord(String userID, UserProfile.InterviewRecord record) {
        UserProfile profile = profiles.computeIfAbsent(userID, k ->
                UserProfile.builder()
                        .userId(k)
                        .skillLevel(new HashMap<>())
                        .weakPoints(new ArrayList<>())
                        .interviewHist(new ArrayList<>())
                        .build());

        synchronized (profile) {
            if (profile.getInterviewHist() == null) {
                profile.setInterviewHist(new ArrayList<>());
            }
            profile.getInterviewHist().add(record);
            profile.setUpdatedAt(LocalDateTime.now());
        }

        if (store != null) {
            store.saveProfile(profile);
        }
    }

    /**
     * 获取用户的薄弱点（淘汰过期 + 按分数排序 + Top N）
     */
    public List<UserProfile.WeakPoint> getWeakPoints(String userID) {
        UserProfile profile = getProfile(userID);
        if (profile == null || profile.getWeakPoints() == null) {
            return Collections.emptyList();
        }

        LocalDateTime now = LocalDateTime.now();

        // 过滤掉超过 30 天的过期薄弱点，按分数升序排序（分数越低越弱），取 Top N
        return profile.getWeakPoints().stream()
                .filter(wp -> wp.getLastSeen() != null
                        && Duration.between(wp.getLastSeen(), now).compareTo(WEAK_POINT_MAX_AGE) <= 0)
                .sorted(Comparator.comparingDouble(UserProfile.WeakPoint::getScore))
                .limit(WEAK_POINT_TOP_N)
                .collect(Collectors.toList());
    }
}
