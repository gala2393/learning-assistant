package com.mytext.learningassistant.admin;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 系统日志数据访问仓库 — 查询管理员操作日志。
 * 提供按创建时间倒序查询所有日志的方法。
 */
public interface SystemLogRepository extends JpaRepository<SystemLogEntity, Long> {

    /** 查询所有日志，按创建时间倒序（最新的在前） */
    List<SystemLogEntity> findAllByOrderByCreatedAtDesc();
}
