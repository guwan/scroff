package com.scroff.server.service;

import com.scroff.server.config.ScroffProperties;
import com.scroff.server.entity.Device;
import com.scroff.server.repository.DeviceRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备连接管理器。
 *
 * 职责：
 * 1. 启动时按需 adb connect 所有 enabled 设备
 * 2. 定时心跳检测，更新 device.status
 * 3. 提供幂等的 ensureConnected()
 *
 * 心跳策略：
 *  - ONLINE → 失败 3 次连续后才转 OFFLINE（容忍瞬时抖动）
 *  - OFFLINE → 成功 1 次即转 ONLINE
 *  - ERROR → 任何成功心跳都恢复到 ONLINE
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceManager {

    private final AdbService adbService;
    private final DeviceRepository deviceRepo;
    private final ScroffProperties props;

    /** address → 连续失败次数 */
    private final ConcurrentHashMap<String, AtomicInteger> failCounters = new ConcurrentHashMap<>();
    /** address → 上次心跳时间（防抖） */
    private final ConcurrentHashMap<String, Long> lastHeartbeat = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (props.getAdb().isAutoConnectOnStartup()) {
            // 异步执行，不阻塞 Spring Boot 启动
            new Thread(this::connectAllOnStartup, "device-manager-init").start();
        }
    }

    @PreDestroy
    void shutdown() {
        log.info("DeviceManager 关闭，清理所有 adb 连接");
        // 不强求 adb disconnect all，让 OS 进程清理即可
    }

    /**
     * 启动时连接所有 enabled 设备
     */
    private void connectAllOnStartup() {
        try {
            Thread.sleep(3000); // 等 Spring 完全启动
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        List<Device> all = deviceRepo.findAllByEnabledTrue();
        log.info("启动时需要连接 {} 台设备", all.size());
        for (Device d : all) {
            try {
                ensureConnected(d);
            } catch (Exception e) {
                log.error("启动连接失败: {}: {}", d.getName(), e.getMessage());
            }
        }
    }

    /**
     * 幂等地确保设备已连接。返回是否最终处于 ONLINE 状态。
     */
    public boolean ensureConnected(Device device) {
        String address = device.getAddress();
        AdbResult r = adbService.connect(device.getHost(), device.getAdbPort());
        if (r.isSuccess() || r.getStdout().contains("already connected")
                || r.getStderr().contains("already connected")) {
            updateStatus(device.getId(), Device.Status.ONLINE, null);
            failCounters.computeIfAbsent(address, k -> new AtomicInteger()).set(0);
            log.info("设备已连接: {} ({})", device.getName(), address);
            return true;
        } else {
            updateStatus(device.getId(), Device.Status.OFFLINE, r.getErrorMessage());
            log.warn("设备连接失败: {} ({}): {}", device.getName(), address, r.getErrorMessage());
            return false;
        }
    }

    /**
     * 心跳检测：定期跑一遍。fixedRate 由 application.yml 的 heartbeat-interval 控制。
     */
    @Scheduled(fixedDelayString = "#{${scroff.adb.heartbeat-interval:30} * 1000}",
               initialDelay = 10_000)
    public void heartbeat() {
        List<Device> all = deviceRepo.findAllByEnabledTrue();
        for (Device d : all) {
            try {
                doHeartbeat(d);
            } catch (Exception e) {
                log.error("心跳异常: {} ({})", d.getName(), d.getAddress(), e);
            }
        }
    }

    private void doHeartbeat(Device d) {
        String address = d.getAddress();
        // 1) 是否还在 adb devices 列表里
        if (!adbService.isOnline(address)) {
            // 不在 → 重新尝试 connect
            log.debug("心跳: {} 不在连接列表，尝试重连", address);
            ensureConnected(d);
            return;
        }
        // 2) 跑一个轻量命令 echo ok
        AdbResult r = adbService.execShell(address, "echo ok");
        AtomicInteger fc = failCounters.computeIfAbsent(address, k -> new AtomicInteger());
        if (r.isSuccess() && r.getStdout().contains("ok")) {
            fc.set(0);
            if (d.getStatus() != Device.Status.ONLINE) {
                updateStatus(d.getId(), Device.Status.ONLINE, null);
                log.info("心跳恢复 ONLINE: {}", d.getName());
            } else {
                // 只更新时间戳
                updateLastSeen(d.getId());
            }
        } else {
            int fails = fc.incrementAndGet();
            log.warn("心跳失败 {}/3: {} ({}) - {}",
                    fails, d.getName(), address, r.getErrorMessage());
            if (fails >= 3 && d.getStatus() != Device.Status.OFFLINE) {
                updateStatus(d.getId(), Device.Status.OFFLINE, r.getErrorMessage());
                fc.set(0);
            }
        }
    }

    @Transactional
    protected void updateStatus(Long id, Device.Status status, String err) {
        deviceRepo.updateStatus(id, status, LocalDateTime.now(), err);
    }

    @Transactional
    protected void updateLastSeen(Long id) {
        deviceRepo.updateStatus(id, Device.Status.ONLINE, LocalDateTime.now(), null);
    }

    /**
     * 手工触发：测试连接。供 web / 设备列表页调用。
     */
    public Device testConnection(Device device) {
        Optional<Device> opt = deviceRepo.findById(device.getId());
        if (opt.isEmpty()) return device;
        Device d = opt.get();
        ensureConnected(d);
        return deviceRepo.findById(d.getId()).orElse(d);
    }
}
