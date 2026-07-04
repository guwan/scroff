package com.scroff.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
        /** adb 可执行文件绝对路径 */
        private String executable = "/usr/bin/adb";
        /** 启动时自动连接所有 enabled 设备 */
        private boolean autoConnectOnStartup = true;
        /** 心跳间隔（秒） */
        private int heartbeatInterval = 30;
        /** 单条 adb 命令超时（毫秒） */
        private long commandTimeout = 10_000;
        /** 关屏/开屏命令模板，{param} 占位 */
        private String screenOffCommand = "cd /sys/kernel/debug/dispdbg && echo disp0 > name && echo blank > command && echo {param} > param && echo 1 > start";
    }

    @Getter
    @Setter
    public static class Schedule {
        private boolean loadOnStartup = true;
    }
}
