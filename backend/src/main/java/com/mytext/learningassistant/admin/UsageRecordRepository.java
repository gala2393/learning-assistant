package com.mytext.learningassistant.admin;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, Long> {

    List<UsageRecordEntity> findAllByOrderByCreatedAtDesc();
}
