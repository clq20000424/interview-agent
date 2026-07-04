package com.interview.agent.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author 陈龙强
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsername(String username);
}
