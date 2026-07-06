package com.scroff.server.service;

import com.scroff.server.entity.Settings;
import com.scroff.server.repository.SettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 系统配置的运行时读写。
 *
 * 与 {@code @ConfigurationProperties} 的区别：
 * - @ConfigurationProperties：绑到 application.yml，重启才生效
 * - ConfigService：存 DB system_config 表，web UI 改完立即生效
 *
 * 用法：上层用 {@code configService.get("xxx")} 取，set/unset 写。
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final SettingsRepository repo;

    /** 取配置值；key 不存在返回 Optional.empty() */
    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return repo.findById(key).map(Settings::getValue);
    }

    /** 取配置值；key 不存在返回默认值 */
    @Transactional(readOnly = true)
    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /** 写配置值（key 不存在则插入，存在则覆盖） */
    @Transactional
    public void set(String key, String value) {
        Settings s = repo.findById(key).orElseGet(Settings::new);
        s.setKey(key);
        s.setValue(value);
        repo.save(s);
    }

    /** 删除配置项 */
    @Transactional
    public void unset(String key) {
        repo.deleteById(key);
    }
}
