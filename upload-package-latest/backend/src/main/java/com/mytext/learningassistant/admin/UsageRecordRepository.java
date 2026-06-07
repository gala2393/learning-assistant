package com.mytext.learningassistant.admin;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 使用记录数据访问仓库 — 查询用户操作记录。
 * 记录用户的问答、资料上传等操作，供管理员审计 token 消耗。
 */
public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, Long> {

    /** 查询所有使用记录，按创建时间倒序 */
    List<UsageRecordEntity> findAllByOrderByCreatedAtDesc();
}
