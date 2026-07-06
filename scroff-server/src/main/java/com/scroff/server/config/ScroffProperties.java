package com.scroff.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取 application.yml 中 scroff.* 配置项。
 */
@Component
@ConfigurationProperties(prefix = "scroff")
@Getter
@Setter
public class ScroffProperties {

    private Adb adb = new Adb();
    private Schedule schedule = new Schedule();

    @Getter
    @Setter
    public static class Adb {
        /**
         * ADB 配置列表。每项可指向不同来源（Android SDK / WSL / 自带包 / 远程），
         * 用户可在 web UI 切换激活项。
         */
        private List<AdbProfile> profiles = new ArrayList<>();
        /**
         * 配置文件里指定的默认激活项 id（运行时可被 system_config 表的
         * "scroff.adb.active-profile-id" 覆盖）。
         */
        private String activeProfileId;
        /** 启动时自动连接所有 enabled 设备 */
        private boolean autoConnectOnStartup = true;
        /** 心跳间隔（秒） */
        private int heartbeatInterval = 30;
        /** 单条 adb 命令超时（毫秒） */
        private long commandTimeout = 10_000;
        /** 关屏/开屏命令模板，{param} 占位 */
        private String screenOffCommand = "cd /sys/kernel/debug/dispdbg && echo disp0 > name && echo blank > command && echo {param} > param && echo 1 > start";

        /**
         * 按 id 找 profile，找不到返回 null。
         * 注意：这里只看 id，不看 enabled——上层调用方决定是否跳过 disabled。
         */
        public AdbProfile findById(String id) {
            if (id == null) return null;
            for (AdbProfile p : profiles) {
                if (id.equals(p.getId())) return p;
            }
            return null;
        }

        /**
         * 取配置文件里默认的激活项（仅参考 activeProfileId + enabled），
         * 找不到时回退到第一个 enabled；都没有则返回 null。
         */
        public AdbProfile defaultActiveProfile() {
            AdbProfile p = findById(activeProfileId);
            if (p != null) return p;
            for (AdbProfile cand : profiles) {
                if (cand.isEnabled()) return cand;
            }
            return null;
        }
    }

    /**
     * 单个 ADB 配置项。id 唯一，name 给人看，executable 是要 spawn 的可执行文件
     * （绝对路径或 PATH 里的命令名），args 会在 executable 之后、ADB 子命令之前插入
     * ——专用于 WSL 之类的"包装器"场景（wsl.exe adb connect ...）。
     */
    @Getter
    @Setter
    public static class AdbProfile {
        /** 唯一 id，建议英文短横线，如 "android-sdk"、"wsl-ubuntu" */
        private String id;
        /** 给 UI 看的显示名 */
        private String name;
        /** adb 可执行文件绝对路径或 PATH 中的命令名 */
        private String executable;
        /** 包装器场景下需要的额外参数，如 ["adb"]（用于 wsl.exe） */
        private List<String> args = new ArrayList<>();
        /** 配置里是否默认启用（运行时不影响切换，但 UI 可作为初始状态） */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Schedule {
        private boolean loadOnStartup = true;
    }
}
