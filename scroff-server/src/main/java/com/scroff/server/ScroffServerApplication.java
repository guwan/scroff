package com.scroff.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scroff 集中控屏服务入口。
 *
 * 功能：
 * 1. 通过 ADB over TCP/IP 连接多台安卓叫号机
 * 2. Web 界面集中管理设备与定时任务
 * 3. 定时触发 / 手动触发 / API 触发三种方式控制屏幕开关
 *
 * 启动方式：
 *   java -jar scroff-server.jar
 * 或开发时：
 *   mvn spring-boot:run
 */
@SpringBootApplication
@EnableScheduling
public class ScroffServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScroffServerApplication.class, args);
    }
}
