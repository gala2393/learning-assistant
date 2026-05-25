package com.mytext.learningassistant.admin;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemLogRepository extends JpaRepository<SystemLogEntity, Long> {

    List<SystemLogEntity> findAllByOrderByCreatedAtDesc();
}
