package com.scroff.server.service;

import com.scroff.server.config.ScroffProperties;
import com.scroff.server.entity.Device;
import com.scroff.server.repository.DeviceRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
 *
 * 关于 self 字段：
 * Spring AOP 代理只能拦截 public 方法的外部调用；同类内 this.xxx() 会绕过代理，
 * 导致 @Transactional 完全失效。注入自身代理 self，所有需要事务的方法都通过
 * self.xxx() 调用——这样既能保持每个 updateStatus 一次事务的粒度，又不会丢事务。
 */
@Slf4j
@Service
public class DeviceManager {

    private final AdbService adbService;
    private final DeviceRepository deviceRepo;
    private final ScroffProperties props;
    private final DeviceManager self;

    /** address → 连续失败次数 */
    private final ConcurrentHashMap<String, AtomicInteger> failCounters = new ConcurrentHashMap<>();
    /** address → 上次心跳时间（防抖） */
    private final ConcurrentHashMap<String, Long> lastHeartbeat = new ConcurrentHashMap<>();

    /** 批量操作线程池（测试连接、查屏状态等并行用） */
    private ExecutorService batchPool;

    public DeviceManager(AdbService adbService,
                         DeviceRepository deviceRepo,
                         ScroffProperties props,
                         @Lazy DeviceManager self) {
        this.adbService = adbService;
        this.deviceRepo = deviceRepo;
        this.props = props;
        this.self = self;
    }

    @PostConstruct
    void init() {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "device-batch-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        batchPool = Executors.newFixedThreadPool(8, tf);
        log.info("DeviceManager 批量线程池就绪 (size=8)");

        if (props.getAdb().isAutoConnectOnStartup()) {
            // 通过 self 调走代理，让 ensureConnected / updateStatus 的 @Transactional 生效
            new Thread(() -> self.connectAllOnStartup(), "device-manager-init").start();
        }
    }

    @PreDestroy
    void shutdown() {
        if (batchPool != null) {
            batchPool.shutdown();
            try {
                if (!batchPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    batchPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                batchPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("DeviceManager 关闭，清理所有 adb 连接");
        // 不强求 adb disconnect all，让 OS 进程清理即可
    }

    /**
     * 启动时连接所有 enabled 设备
     */
    public void connectAllOnStartup() {
        try {
            Thread.sleep(3000); // 等 Spring 完全启动
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        List<Device> all = deviceRepo.findAllByEnabledTrue();
        log.info("启动时需要连接 {} 台设备", all.size());
        for (Device d : all) {
            try {
                self.ensureConnected(d);
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
            self.updateStatus(device.getId(), Device.Status.ONLINE, null);
            failCounters.computeIfAbsent(address, k -> new AtomicInteger()).set(0);
            log.info("设备已连接: {} ({})", device.getName(), address);
            return true;
        } else {
            self.updateStatus(device.getId(), Device.Status.OFFLINE, r.getErrorMessage());
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
                self.doHeartbeat(d);
            } catch (Exception e) {
                log.error("心跳异常: {} ({})", d.getName(), d.getAddress(), e);
            }
        }
    }

    public void doHeartbeat(Device d) {
        String address = d.getAddress();
        // 1) 是否还在 adb devices 列表里
        if (!adbService.isOnline(address)) {
            // 不在 → 重新尝试 connect
            log.debug("心跳: {} 不在连接列表，尝试重连", address);
            self.ensureConnected(d);
            return;
        }
        // 2) 跑一个轻量命令 echo ok
        AdbResult r = adbService.execShell(address, "echo ok");
        AtomicInteger fc = failCounters.computeIfAbsent(address, k -> new AtomicInteger());
        if (r.isSuccess() && r.getStdout().contains("ok")) {
            fc.set(0);
            if (d.getStatus() != Device.Status.ONLINE) {
                self.updateStatus(d.getId(), Device.Status.ONLINE, null);
                log.info("心跳恢复 ONLINE: {}", d.getName());
            } else {
                self.updateLastSeen(d.getId());
            }
        } else {
            int fails = fc.incrementAndGet();
            log.warn("心跳失败 {}/3: {} ({}) - {}",
                    fails, d.getName(), address, r.getErrorMessage());
            if (fails >= 3 && d.getStatus() != Device.Status.OFFLINE) {
                self.updateStatus(d.getId(), Device.Status.OFFLINE, r.getErrorMessage());
                fc.set(0);
            }
        }
    }

    @Transactional
    public void updateStatus(Long id, Device.Status status, String err) {
        deviceRepo.updateStatus(id, status, LocalDateTime.now(), err);
    }

    @Transactional
    public void updateLastSeen(Long id) {
        deviceRepo.updateStatus(id, Device.Status.ONLINE, LocalDateTime.now(), null);
    }

    /**
     * 手工触发：测试连接。供 web / 设备列表页调用。
     */
    public Device testConnection(Device device) {
        Optional<Device> opt = deviceRepo.findById(device.getId());
        if (opt.isEmpty()) return device;
        Device d = opt.get();
        self.ensureConnected(d);
        return deviceRepo.findById(d.getId()).orElse(d);
    }

    /**
     * 检查设备的屏幕状态（只读，不控制）。
     *
     * <p>流程：
     * <ol>
     *   <li>{@code adb connect} 一次，确保连上</li>
     *   <li>查 adb devices 拿到当前真实 status（OFFLINE / ONLINE / UNAUTHORIZED）</li>
     *   <li>如果在线，执行 {@code dumpsys power | grep "Display Power"} 拿 {@code state=ON|OFF}</li>
     *   <li>返回结果；解析失败时 screenOn=null 表示"未知"</li>
     * </ol>
     */
    public ScreenStatusResult checkScreenStatus(Device device) {
        // 1) 先 ensureConnected，把 status 字段刷到最新
        self.ensureConnected(device);
        // 拿一次最新状态（ensureConnected 内部已经写库了）
        Device fresh = deviceRepo.findById(device.getId()).orElse(device);

        boolean online = fresh.getStatus() == Device.Status.ONLINE;
        Boolean screenOn = null;       // null = 未知
        String rawOutput = "";
        String message;

        if (!online) {
            String err = fresh.getLastError();
            message = "设备不在线"
                    + (err != null && !err.isBlank() ? "：" + err : "");
            return new ScreenStatusResult(fresh.getId(), fresh.getName(), online, screenOn, message, rawOutput);
        }

        // 2) 在线 → 查 dumpsys power
        AdbResult r = adbService.execShell(fresh.getAddress(),
                "dumpsys power | grep 'Display Power'");
        if (!r.isSuccess()) {
            message = "查询屏幕状态失败：" + r.getErrorMessage();
            return new ScreenStatusResult(fresh.getId(), fresh.getName(), online, screenOn, message, rawOutput);
        }
        rawOutput = r.getStdout().trim();

        // 3) 解析 "Display Power: state=ON" / "state=OFF"
        //    某些 ROM 字段顺序可能变，用宽松匹配
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("state\\s*=\\s*(ON|OFF)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(rawOutput);
        if (m.find()) {
            screenOn = "ON".equalsIgnoreCase(m.group(1));
            message = "设备在线，屏幕 " + (screenOn ? "ON（开）" : "OFF（关）");
        } else {
            // 4) 兜底：尝试 dumpsys display
            AdbResult r2 = adbService.execShell(fresh.getAddress(),
                    "dumpsys display | grep mScreenState");
            if (r2.isSuccess()) {
                String out2 = r2.getStdout().trim();
                java.util.regex.Matcher m2 = java.util.regex.Pattern
                        .compile("mScreenState\\s*=\\s*(ON|OFF)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(out2);
                if (m2.find()) {
                    screenOn = "ON".equalsIgnoreCase(m2.group(1));
                    message = "设备在线，屏幕 " + (screenOn ? "ON（开）" : "OFF（关）");
                } else {
                    message = "设备在线，但无法解析屏幕状态。原始输出：" + out2;
                }
            } else {
                message = "设备在线，但 dumpsys 命令无输出。原始输出：" + rawOutput;
            }
        }
        return new ScreenStatusResult(fresh.getId(), fresh.getName(), online, screenOn, message, rawOutput);
    }

    /**
     * 屏幕状态查询结果（暴露给 Controller 用，便于序列化成 JSON）。
     */
    public record ScreenStatusResult(Long deviceId,
                                     String deviceName,
                                     boolean online,
                                     Boolean screenOn,   // null = 未知
                                     String message,
                                     String rawOutput) {}

    /**
     * 批量测试所有启用设备的连接（页面"一键测连"按钮用）。
     * 并发执行，线程池 size=8，结果按调用顺序收集。
     */
    public BatchTestResult testAllConnections() {
        List<Device> devices = deviceRepo.findAllByEnabledTrue();
        if (devices.isEmpty()) {
            return new BatchTestResult(0, 0, 0, List.of());
        }
        log.info("批量测试连接开始，设备数={}", devices.size());
        List<CompletableFuture<DeviceTestResult>> futures = new ArrayList<>(devices.size());
        for (Device d : devices) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    self.testConnection(d);
                    Device fresh = deviceRepo.findById(d.getId()).orElse(d);
                    boolean online = fresh.getStatus() == Device.Status.ONLINE;
                    String err = fresh.getLastError();
                    return new DeviceTestResult(d.getId(), d.getName(), online, err);
                } catch (Exception e) {
                    log.error("批量测连异常: device={}", d.getName(), e);
                    return new DeviceTestResult(d.getId(), d.getName(), false, "异常: " + e.getMessage());
                }
            }, batchPool));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("批量测连等待超时或中断", e);
        }
        int online = 0, offline = 0;
        List<DeviceTestResult> results = new ArrayList<>();
        for (CompletableFuture<DeviceTestResult> f : futures) {
            if (!f.isDone()) {
                results.add(new DeviceTestResult(0L, "<timeout>", false, "超时未返回"));
                offline++;
                continue;
            }
            DeviceTestResult r = f.join();
            results.add(r);
            if (r.online()) online++; else offline++;
        }
        log.info("批量测连完成: 总={} 在线={} 离线={}", devices.size(), online, offline);
        return new BatchTestResult(devices.size(), online, offline, results);
    }

    /**
     * 批量查询所有启用设备的屏幕状态（页面"一键查屏"按钮用）。
     */
    public BatchStatusResult checkAllScreenStatus() {
        List<Device> devices = deviceRepo.findAllByEnabledTrue();
        if (devices.isEmpty()) {
            return new BatchStatusResult(0, 0, 0, 0, 0, List.of());
        }
        log.info("批量查屏状态开始，设备数={}", devices.size());
        List<CompletableFuture<DeviceStatusResult>> futures = new ArrayList<>(devices.size());
        for (Device d : devices) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    ScreenStatusResult r = self.checkScreenStatus(d);
                    return new DeviceStatusResult(r.deviceId(), r.deviceName(), r.online(), r.screenOn(), r.message());
                } catch (Exception e) {
                    log.error("批量查屏异常: device={}", d.getName(), e);
                    return new DeviceStatusResult(d.getId(), d.getName(), false, null, "异常: " + e.getMessage());
                }
            }, batchPool));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("批量查屏等待超时或中断", e);
        }
        int on = 0, off = 0, unknown = 0, offline = 0;
        List<DeviceStatusResult> results = new ArrayList<>();
        for (CompletableFuture<DeviceStatusResult> f : futures) {
            if (!f.isDone()) {
                results.add(new DeviceStatusResult(0L, "<timeout>", false, null, "超时未返回"));
                offline++;
                continue;
            }
            DeviceStatusResult r = f.join();
            results.add(r);
            if (!r.online()) offline++;
            else if (r.screenOn() == null) unknown++;
            else if (r.screenOn()) on++;
            else off++;
        }
        log.info("批量查屏完成: 总={} 在线开={} 在线关={} 在线未知={} 离线={}",
                devices.size(), on, off, unknown, offline);
        return new BatchStatusResult(devices.size(), on, off, unknown, offline, results);
    }

    /** 单台设备测连结果 */
    public record DeviceTestResult(Long deviceId, String deviceName, boolean online, String error) {}

    /** 单台设备查屏结果 */
    public record DeviceStatusResult(Long deviceId, String deviceName, boolean online, Boolean screenOn, String message) {}

    /** 批量测连汇总 */
    public record BatchTestResult(int total, int online, int offline, List<DeviceTestResult> results) {}

    /** 批量查屏汇总 */
    public record BatchStatusResult(int total, int screenOnCount, int screenOffCount,
                                    int unknownCount, int offlineCount,
                                    List<DeviceStatusResult> results) {}
}
