package com.scroff.server.service;

import com.scroff.server.config.ScroffProperties;
import com.scroff.server.entity.Device;
import com.scroff.server.entity.Schedule;
import com.scroff.server.entity.ScreenLog;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.repository.ScheduleRepository;
import com.scroff.server.repository.ScreenLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务层：开屏/关屏。
 *
 * 流程：
 *   1. 查 device
 *   2. 调用 adb -s host:port shell "...命令..."
 *   3. 无论成功失败都写 screen_log
 *   4. 如果是 schedule 触发，回写 schedule.last_run_*
 */
@Slf4j
@Service
public class ScreenPowerService {

    private final AdbService adbService;
    private final DeviceManager deviceManager;
    private final DeviceRepository deviceRepo;
    private final ScheduleRepository scheduleRepo;
    private final ScreenLogRepository logRepo;
    private final ScroffProperties props;

    /**
     * 自注入：{@link #controlAll} 内部并发调用 {@link #control} 时，
     * 必须走 Spring 代理才能让 {@code @Transactional} 生效。
     *
     * <p><b>关于 @Lazy 必须在构造器参数上</b>：{@code @Lazy} 放在字段上 + Lombok
     * {@code @RequiredArgsConstructor} 时，Lombok 不会把 {@code @Lazy} 传播到构造器
     * 参数，Spring 仍然按字段类型直接注入真正的 bean，导致构造器循环引用（self ← self），
     * Spring Boot 3.x 默认会拒绝启动并报
     * "The dependencies of some of the beans form a cycle"。
     * 解法：写显式构造器，把 {@code @Lazy} 直接放在参数上（参考 {@link DeviceManager}）。
     */
    private final ScreenPowerService self;

    /** 批量控制专用线程池，与 adbPool、Tomcat 线程池都隔离 */
    private ExecutorService batchPool;

    public ScreenPowerService(AdbService adbService,
                              DeviceManager deviceManager,
                              DeviceRepository deviceRepo,
                              ScheduleRepository scheduleRepo,
                              ScreenLogRepository logRepo,
                              ScroffProperties props,
                              @Lazy ScreenPowerService self) {
        this.adbService = adbService;
        this.deviceManager = deviceManager;
        this.deviceRepo = deviceRepo;
        this.scheduleRepo = scheduleRepo;
        this.logRepo = logRepo;
        this.props = props;
        this.self = self;
    }

    @PostConstruct
    void initPool() {
        // 8 个并发足够，太多会同时打满 adb server 和网络
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "screen-batch-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        batchPool = Executors.newFixedThreadPool(8, tf);
        log.info("ScreenPowerService 批量线程池就绪 (size=8)");
    }

    @PreDestroy
    void shutdownPool() {
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
    }

    /**
     * 控制单台设备屏幕开关。返回执行结果消息。
     */
    @Transactional
    public String control(Long deviceId, boolean powerOn, ScreenLog.TriggerType trigger) {
        Optional<Device> opt = deviceRepo.findById(deviceId);
        if (opt.isEmpty()) {
            String msg = "设备不存在: id=" + deviceId;
            log.warn(msg);
            return msg;
        }
        Device device = opt.get();
        if (!device.getEnabled()) {
            String msg = "设备已禁用: " + device.getName();
            log.warn(msg);
            return msg;
        }

        // 1) 设备没连上先尝试重连
        if (!adbService.isOnline(device.getAddress())) {
            log.info("设备未连接，尝试自动重连: {}", device.getName());
            boolean ok = deviceManager.ensureConnected(device);
            if (!ok) {
                writeLog(device, powerOn, trigger, false, "设备不在线，connect 失败");
                return "设备不在线: " + device.getName();
            }
        }

        // 2) 构造命令
        String param = powerOn ? "0" : "1";
        String cmd = props.getAdb().getScreenOffCommand().replace("{param}", param);
        String action = powerOn ? "开屏" : "关屏";

        // 3) 执行
        AdbResult r = adbService.execShell(device.getAddress(), cmd);
        boolean success = r.isSuccess();
        String message = success
                ? action + "成功"
                : (action + "失败: " + r.getErrorMessage());

        log.info("{} {} ({}) -> {}", action, device.getName(), device.getAddress(),
                success ? "OK" : "FAIL: " + r.getErrorMessage());

        writeLog(device, powerOn, trigger, success, message);
        return message;
    }

    /**
     * 定时任务专用：执行后回写 schedule.last_run_*
     */
    @Transactional
    public void runSchedule(Long scheduleId) {
        Optional<Schedule> opt = scheduleRepo.findById(scheduleId);
        if (opt.isEmpty()) return;
        Schedule s = opt.get();
        if (!s.getEnabled()) {
            log.debug("schedule {} 已禁用，跳过", s.getId());
            return;
        }
        boolean powerOn = (s.getAction() == Schedule.Action.ON);
        String msg;
        if (s.isForAllDevices()) {
            // 对所有启用设备生效：复用 /devices/all/on|/off 同样的并发批量逻辑
            BatchControlResult r = controlAll(powerOn, ScreenLog.TriggerType.SCHEDULE);
            msg = r.summary();
        } else {
            // 对单台设备生效（原行为）
            msg = control(s.getDeviceId(), powerOn, ScreenLog.TriggerType.SCHEDULE);
        }
        Schedule.LastRunStatus status = msg.contains("失败")
                ? Schedule.LastRunStatus.FAILED
                : Schedule.LastRunStatus.SUCCESS;
        // 截断到 500 字符
        if (msg.length() > 480) msg = msg.substring(0, 480) + "...";
        scheduleRepo.updateLastRun(s.getId(), LocalDateTime.now(), status, msg);
    }

    private void writeLog(Device device, boolean powerOn,
                          ScreenLog.TriggerType trigger, boolean success, String message) {
        ScreenLog log = new ScreenLog();
        log.setDeviceId(device.getId());
        log.setDeviceName(device.getName());
        log.setAction(powerOn ? ScreenLog.Action.ON : ScreenLog.Action.OFF);
        log.setTriggerType(trigger);
        log.setSuccess(success);
        if (message != null && message.length() > 990) {
            message = message.substring(0, 980) + "...";
        }
        log.setMessage(message);
        log.setExecutedAt(LocalDateTime.now());
        logRepo.save(log);
    }

    /**
     * 批量控制所有启用设备（"全部开"/"全部关"按钮专用）。
     *
     * <ul>
     *   <li>只处理 {@code enabled = true} 的设备，禁用的跳过</li>
     *   <li>并发执行，线程池 size = 8（与 Tomcat / adb pool 隔离）</li>
     *   <li>每台设备的 adb 命令和 screen_log 写库相互独立（{@code @Transactional} 走 self 代理）</li>
     *   <li>返回汇总消息，失败列表最多列 5 条避免 flash 过长</li>
     * </ul>
     *
     * @return 给用户看的汇总消息
     */
    public BatchControlResult controlAll(boolean powerOn, ScreenLog.TriggerType trigger) {
        List<Device> devices = deviceRepo.findAllByEnabledTrue();
        if (devices.isEmpty()) {
            return new BatchControlResult(0, 0, List.of(), "没有启用的设备");
        }

        String action = powerOn ? "开屏" : "关屏";
        log.info("批量{}开始，设备数={}", action, devices.size());

        // 并发提交到 batchPool。lambda 里调 self.control()，必须走代理才能触发 @Transactional
        List<CompletableFuture<DeviceResult>> futures = new ArrayList<>(devices.size());
        for (Device d : devices) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    String msg = self.control(d.getId(), powerOn, trigger);
                    boolean ok = !(msg.contains("失败") || msg.contains("不在线")
                            || msg.contains("不存在") || msg.contains("已禁用"));
                    return new DeviceResult(d, ok, msg);
                } catch (Exception e) {
                    log.error("批量{}异常: device={}", action, d.getName(), e);
                    return new DeviceResult(d, false, "异常: " + e.getMessage());
                }
            }, batchPool));
        }

        // 等所有完成（带超时保护，避免某一台卡死把整个批量卡住）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("批量{}等待超时或中断，返回部分结果", action, e);
        }

        int success = 0, failed = 0;
        List<String> failureMsgs = new ArrayList<>();
        for (CompletableFuture<DeviceResult> f : futures) {
            if (!f.isDone()) {
                failed++;
                failureMsgs.add("超时未返回");
                continue;
            }
            DeviceResult r = f.join();
            if (r.ok) {
                success++;
            } else {
                failed++;
                failureMsgs.add(r.device.getName() + ": " + r.message);
            }
        }

        String summary = String.format("批量%s: %d/%d 成功", action, success, devices.size());
        if (failed > 0) {
            // 截断到 5 条，避免 flash 消息过长
            List<String> shown = failureMsgs.size() > 5
                    ? new ArrayList<>(failureMsgs.subList(0, 5))
                    : failureMsgs;
            if (failureMsgs.size() > 5) {
                shown.add("... 还有 " + (failureMsgs.size() - 5) + " 个失败未显示");
            }
            summary += "，失败: " + String.join("; ", shown);
        }
        log.info("批量{}完成: {}", action, summary);
        return new BatchControlResult(success, failed, failureMsgs, summary);
    }

    /** 单台设备执行结果（内部用） */
    private record DeviceResult(Device device, boolean ok, String message) {}

    /**
     * 批量执行汇总结果（暴露给 Controller 用）。
     */
    public record BatchControlResult(int success, int failed,
                                     List<String> failures,
                                     String summary) {
        public boolean allSuccess() {
            return failed == 0;
        }
    }
}
