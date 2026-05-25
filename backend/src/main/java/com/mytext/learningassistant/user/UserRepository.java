package com.mytext.learningassistant.user;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    List<UserEntity> findAllByOrderByCreatedAtDesc();

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRole(UserRole role);
}
