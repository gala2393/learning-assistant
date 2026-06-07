package com.mytext.learningassistant.user;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户数据访问仓库 — Spring Data JPA 自动实现接口，不需要写 SQL。
 * <p>
 * 继承 JpaRepository&lt;UserEntity, Long&gt; 后自动获得 save/findById/findAll/deleteById/count 等方法。
 * <p>
 * 自定义查询方法按命名规则自动生成 SQL：
 * <ul>
 *   <li>findByUsername("admin") → SELECT * FROM sys_user WHERE username = 'admin'</li>
 *   <li>existsByUsername("admin") → SELECT COUNT(*) > 0 FROM sys_user WHERE username = 'admin'</li>
 *   <li>findAllByOrderByCreatedAtDesc() → SELECT * FROM sys_user ORDER BY created_at DESC</li>
 * </ul>
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    /** 查询所有用户，按创建时间倒序（最新的在前） */
    List<UserEntity> findAllByOrderByCreatedAtDesc();

    /** 按用户名查找，用于登录 */
    Optional<UserEntity> findByUsername(String username);

    /** 按邮箱查找，用于邮箱登录 */
    Optional<UserEntity> findByEmail(String email);

    /** 检查用户名是否已存在，用于注册时实时校验 */
    boolean existsByUsername(String username);

    /** 检查邮箱是否已注册 */
    boolean existsByEmail(String email);

    /** 统计指定角色的用户数量 */
    long countByRole(UserRole role);
}
