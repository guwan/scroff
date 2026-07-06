package com.scroff.server.repository;

import com.scroff.server.entity.Settings;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 系统配置的 JPA 仓库。
 * 主键即 key，省掉自定义查询。
 */
public interface SettingsRepository extends JpaRepository<Settings, String> {
}
