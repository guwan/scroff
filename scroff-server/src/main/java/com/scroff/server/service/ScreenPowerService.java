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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * <p>兼容旧调用方（MANUAL / API）—— schedule 字段为 null。
     */
    @Transactional
    public String control(Long deviceId, boolean powerOn, ScreenLog.TriggerType trigger) {
        return doControl(deviceId, powerOn, trigger, null, null);
    }

    /**
     * 由 schedule 触发的控制。trigger 固定为 SCHEDULE，scheduleId/scheduleName 写入日志便于追溯。
     */
    @Transactional
    public String control(Long deviceId, boolean powerOn, ScreenLog.TriggerType trigger,
                          Long scheduleId, String scheduleName) {
        return doControl(deviceId, powerOn, trigger, scheduleId, scheduleName);
    }

    /**
     * 实际执行：参数校验、重连、构造命令、跑 adb、写日志。
     * <p>事务边界在 {@link #control} 上；此处不再开新事务（私有方法 self-call 也会被忽略）。
     * <p>耗时测量：从入参校验通过开始，到 adb 返回结束（含设备不在线时的 connect 重试）。
     */
    private String doControl(Long deviceId, boolean powerOn, ScreenLog.TriggerType trigger,
                             Long scheduleId, String scheduleName) {
        // 整个流程的总耗时（含 DB 查询 / 重连 / adb 命令）
        long startMs = System.currentTimeMillis();

        Optional<Device> opt = deviceRepo.findById(deviceId);
        if (opt.isEmpty()) {
            String msg = "设备不存在: id=" + deviceId;
            log.warn(msg);
            // 设备不存在时不写日志（无 device 信息可写）
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
                int durationMs = (int) (System.currentTimeMillis() - startMs);
                writeLog(device, powerOn, trigger, false, "设备不在线，connect 失败",
                        scheduleId, scheduleName, durationMs);
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

        log.info("{} {} ({}) -> {} ({}ms)", action, device.getName(), device.getAddress(),
                success ? "OK" : "FAIL: " + r.getErrorMessage(),
                System.currentTimeMillis() - startMs);

        int durationMs = (int) (System.currentTimeMillis() - startMs);
        writeLog(device, powerOn, trigger, success, message,
                scheduleId, scheduleName, durationMs);
        return message;
    }

    /**
     * 定时任务专用：执行后回写 schedule.last_run_*
     * <p>按 action 类型分发：
     * <ul>
     *   <li>ON / OFF → 走原有 {@link #control} 开关屏流程</li>
     *   <li>OPEN_FILE → 执行 am start 打开指定文件</li>
     *   <li>OPEN_APP  → 执行 am start / monkey 打开指定应用</li>
     * </ul>
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

        String msg;
        switch (s.getAction()) {
            case ON, OFF -> msg = runPowerAction(s);
            case OPEN_FILE -> msg = runOpenAction(s);
            case OPEN_APP  -> msg = runOpenAction(s);
            default -> msg = "未知动作类型: " + s.getAction();
        }

        Schedule.LastRunStatus status = msg.contains("失败")
                ? Schedule.LastRunStatus.FAILED
                : Schedule.LastRunStatus.SUCCESS;
        // 截断到 500 字符
        if (msg.length() > 480) msg = msg.substring(0, 480) + "...";
        scheduleRepo.updateLastRun(s.getId(), LocalDateTime.now(), status, msg);
    }

    /** ON/OFF 动作执行分支：支持"所有设备"和"指定多台设备"两种模式。 */
    private String runPowerAction(Schedule s) {
        boolean powerOn = (s.getAction() == Schedule.Action.ON);
        if (s.isForAllDevices()) {
            // 所有设备模式
            // 新数据（schedule_device 关联表）+ 老数据兜底（device_id 主列还没迁移的）
            List<Long> overriddenIds = new ArrayList<>(scheduleRepo.findOverridingDeviceIds(s.getCron()));
            overriddenIds.addAll(scheduleRepo.findOverridingDeviceIdsLegacy(s.getCron()));
            // 去重
            overriddenIds = overriddenIds.stream().distinct().toList();
            if (!overriddenIds.isEmpty()) {
                log.info("schedule {}（所有设备）检测到 {} 台设备被同 cron 的定时器覆盖，跳过本批次",
                        s.getId(), overriddenIds.size());
            }
            BatchControlResult r = controlAllExcept(powerOn, overriddenIds, ScreenLog.TriggerType.SCHEDULE,
                    s.getId(), s.getName());
            return r.summary();
        } else {
            // 指定设备模式：遍历 getDeviceIdList()（兼容老单台数据）
            List<Long> ids = s.getDeviceIdList();
            if (ids.isEmpty()) return "未指定任何设备";
            BatchControlResult r = controlByIds(ids, powerOn, ScreenLog.TriggerType.SCHEDULE,
                    s.getId(), s.getName());
            return r.summary();
        }
    }

    /**
     * 批量 ON/OFF：按 deviceId 列表执行（不额外查 enabled）。
     * 复用 controlAllExcept 的并发 + 日志逻辑。
     */
    public BatchControlResult controlByIds(List<Long> ids, boolean powerOn, ScreenLog.TriggerType trigger,
                                            Long scheduleId, String scheduleName) {
        if (ids == null || ids.isEmpty()) {
            return new BatchControlResult(0, 0, List.of(), "没有指定设备");
        }

        // 查出设备并过滤掉不存在/禁用的
        List<Device> devices = deviceRepo.findAllById(ids).stream()
                .filter(d -> d.getEnabled())
                .toList();

        if (devices.isEmpty()) {
            return new BatchControlResult(0, 0, List.of(), "没有启用的设备可执行");
        }

        String action = powerOn ? "开屏" : "关屏";
        List<CompletableFuture<DeviceResult>> futures = new ArrayList<>(devices.size());
        for (Device d : devices) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    String m = self.control(d.getId(), powerOn, trigger, scheduleId, scheduleName);
                    boolean ok = !(m.contains("失败") || m.contains("不在线")
                            || m.contains("不存在") || m.contains("已禁用"));
                    return new DeviceResult(d, ok, m);
                } catch (Exception e) {
                    log.error("批量{}异常: device={}", action, d.getName(), e);
                    return new DeviceResult(d, false, "异常: " + e.getMessage());
                }
            }, batchPool));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("批量{}等待超时或中断", action, e);
        }
        int success = 0, failed = 0;
        List<String> failureMsgs = new ArrayList<>();
        for (CompletableFuture<DeviceResult> f : futures) {
            if (!f.isDone()) { failed++; failureMsgs.add("超时未返回"); continue; }
            DeviceResult r = f.join();
            if (r.ok) success++; else { failed++; failureMsgs.add(r.device.getName() + ": " + r.message); }
        }
        String summary = String.format("批量%s: %d/%d 成功", action, success, devices.size());
        if (failed > 0) {
            List<String> shown = failureMsgs.size() > 5
                    ? new ArrayList<>(failureMsgs.subList(0, 5)) : failureMsgs;
            if (failureMsgs.size() > 5) shown.add("... 还有 " + (failureMsgs.size() - 5) + " 个失败");
            summary += "，失败: " + String.join("; ", shown);
        }
        return new BatchControlResult(success, failed, failureMsgs, summary);
    }

    /**
     * 执行 OPEN_FILE / OPEN_APP 动作。
     * <p>所有设备模式：遍历所有启用设备，并发执行；
     * 指定设备模式：遍历 getDeviceIdList()，并发执行。
     */
    private String runOpenAction(Schedule s) {
        if (s.getTargetPath() == null || s.getTargetPath().isBlank()) {
            return "动作目标为空，请先配置文件路径或应用包名";
        }
        List<Device> devices;
        if (s.isForAllDevices()) {
            devices = deviceRepo.findAllByEnabledTrue();
        } else {
            List<Long> ids = s.getDeviceIdList();
            if (ids.isEmpty()) return "未指定任何设备";
            devices = deviceRepo.findAllById(ids).stream()
                    .filter(d -> d.getEnabled())
                    .toList();
        }
        if (devices.isEmpty()) return "没有启用的设备可执行";

        List<CompletableFuture<String>> futures = new ArrayList<>(devices.size());
        for (Device d : devices) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    doOpenOnDevice(d, s, d.getId(), d.getName()), batchPool));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("批量执行OPEN动作等待超时", e);
        }
        int ok = 0, fail = 0;
        List<String> fails = new ArrayList<>();
        for (CompletableFuture<String> f : futures) {
            if (!f.isDone()) { fail++; continue; }
            String m = f.join();
            if (m.contains("失败") || m.contains("不在线")) {
                fail++;
                fails.add(m);
            } else {
                ok++;
            }
        }
        String actionName = s.getAction() == Schedule.Action.OPEN_FILE ? "打开文件" : "打开应用";
        String summary = String.format("批量%s: %d/%d 成功", actionName, ok, devices.size());
        if (fail > 0) {
            summary += "，失败: " + (fails.size() > 5
                    ? String.join("; ", fails.subList(0, 5)) + "...(+" + (fails.size()-5) + ")"
                    : String.join("; ", fails));
        }
        return summary;
    }

    /**
     * 在单台设备上执行 OPEN_FILE / OPEN_APP。
     */
    private String doOpenOnDevice(Device device, Schedule s, Long scheduleId, String scheduleName) {
        long startMs = System.currentTimeMillis();
        ScreenLog.Action logAction = toScreenLogAction(s.getAction());
        if (device == null) {
            String msg = "设备不存在";
            return msg; // 无 device 信息，无法写日志
        }
        if (!device.getEnabled()) {
            String msg = "设备已禁用: " + device.getName();
            writeLog(device, logAction, ScreenLog.TriggerType.SCHEDULE, false, msg, scheduleId, scheduleName,
                    (int) (System.currentTimeMillis() - startMs));
            return msg;
        }
        if (!adbService.isOnline(device.getAddress())) {
            log.info("设备未连接，尝试自动重连: {}", device.getName());
            boolean ok = deviceManager.ensureConnected(device);
            if (!ok) {
                String msg = "设备不在线，connect 失败";
                writeLog(device, logAction, ScreenLog.TriggerType.SCHEDULE, false, msg, scheduleId, scheduleName,
                        (int) (System.currentTimeMillis() - startMs));
                return msg;
            }
        }

        String target = s.getTargetPath();
        String actionLabel = s.getAction() == Schedule.Action.OPEN_FILE ? "打开文件" : "打开应用";
        // 给 cmd 一个初始值，编译器能确认必然初始化
        String cmd = "echo 'no-op'";
        if (s.getAction() == Schedule.Action.OPEN_FILE) {
            // 自动判断 MIME type
            String mime = guessMime(target);
            // 不指定 component，让 Android 自己选合适的应用打开（视频播放器、图片查看器等）
            // 注意：shell 单引号里不能直接放单引号，所以路径必须安全。
            // 如果路径含单引号则用双引号方案转义。
            String safeTarget = shellSingleQuote(target);
            String safeMime = shellSingleQuote(mime);
            cmd = String.format(
                    "am start -W -a android.intent.action.VIEW -d 'file://%s' -t '%s'",
                    safeTarget, safeMime);
        } else {
            // OPEN_APP：先尝试用 cmd package resolve-activity 拿 launcher activity，
            // 如果 target 已经是 component 格式（带 /），直接用
            if (target.contains("/")) {
                cmd = "am start -W -n " + escapeAdbArg(target);
            } else {
                // 只有包名，先 resolve launcher activity
                AdbResult resolve = adbService.execShell(device.getAddress(),
                        "cmd package resolve-activity --brief " + escapeAdbArg(target));
                boolean resolved = false;
                if (resolve.isSuccess()) {
                    for (String line : resolve.getStdout().split("\\r?\\n")) {
                        line = line.trim();
                        if (line.contains("/") && line.startsWith(target)) {
                            cmd = "am start -W -n " + escapeAdbArg(line);
                            resolved = true;
                            break;
                        }
                    }
                }
                if (!resolved) {
                    // fallback 用 monkey
                    cmd = "monkey -p " + escapeAdbArg(target) + " -c android.intent.category.LAUNCHER 1";
                }
            }
        }

        AdbResult r = adbService.execShell(device.getAddress(), cmd);
        boolean success = r.isSuccess();
        String message = success
                ? actionLabel + "成功: " + shortTarget(target)
                : (actionLabel + "失败: " + r.getErrorMessage() + " (target=" + shortTarget(target) + ")");

        log.info("{} {} ({}) -> {} ({}ms)", actionLabel, device.getName(), device.getAddress(),
                success ? "OK" : "FAIL: " + r.getErrorMessage(),
                System.currentTimeMillis() - startMs);

        int durationMs = (int) (System.currentTimeMillis() - startMs);
        writeLog(device, logAction, ScreenLog.TriggerType.SCHEDULE, success, message, scheduleId, scheduleName, durationMs);
        return message;
    }

    /**
     * 粗略根据扩展名猜 MIME type。只有几个常见格式，覆盖不到就返回 application/octet-stream。
     */
    private static String guessMime(String path) {
        if (path == null) return "application/octet-stream";
        String lower = path.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".3gp")) return "video/3gpp";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".ts") || lower.endsWith(".m2ts")) return "video/mp2t";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    /**
     * adb shell 参数转义：含空格时用单引号包起来。
     */
    private static String escapeAdbArg(String arg) {
        if (arg == null) return "''";
        if (!arg.contains(" ") && !arg.contains("'")) return arg;
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * 把值安全地放入 shell 单引号字符串。
     * 与 {@link #escapeAdbArg} 不同：这里假设外面已经有一层 '...' 包裹，
     * 所以只需处理内部单引号（' → '\''）。
     * <p>
     * 例：shellSingleQuote("/path/with'space") → /path/with'\''space
     * 然后外面 'file://%s' 拼成 'file:///path/with'\''space'
     */
    private static String shellSingleQuote(String arg) {
        if (arg == null) return "";
        return arg.replace("'", "'\\''");
    }

    /**
     * 用于日志里显示的简短 target，避免太长。
     */
    private static String shortTarget(String t) {
        if (t == null) return "";
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }

    /**
     * 把 Schedule.Action 转换成 ScreenLog.Action（仅用于日志）。
     */
    private static ScreenLog.Action toScreenLogAction(Schedule.Action a) {
        return switch (a) {
            case ON -> ScreenLog.Action.ON;
            case OFF -> ScreenLog.Action.OFF;
            case OPEN_FILE -> ScreenLog.Action.OPEN_FILE;
            case OPEN_APP -> ScreenLog.Action.OPEN_APP;
        };
    }

    /**
     * 简化版 writeLog：直接接受 ScreenLog.Action，供 OPEN_FILE / OPEN_APP 复用。
     */
    private void writeLog(Device device, ScreenLog.Action screenAction,
                          ScreenLog.TriggerType trigger, boolean success, String message,
                          Long scheduleId, String scheduleName, Integer durationMs) {
        ScreenLog log = new ScreenLog();
        log.setDeviceId(device.getId());
        log.setDeviceName(device.getName());
        log.setDeviceAddress(device.getAddress());
        log.setAction(screenAction);
        log.setTriggerType(trigger);
        log.setScheduleId(scheduleId);
        log.setScheduleName(scheduleName);
        log.setSuccess(success);
        if (message != null && message.length() > 990) {
            message = message.substring(0, 980) + "...";
        }
        log.setMessage(message);
        log.setDurationMs(durationMs);
        log.setExecutedAt(LocalDateTime.now());
        logRepo.save(log);
    }

    private void writeLog(Device device, boolean powerOn,
                          ScreenLog.TriggerType trigger, boolean success, String message,
                          Long scheduleId, String scheduleName, Integer durationMs) {
        ScreenLog log = new ScreenLog();
        log.setDeviceId(device.getId());
        log.setDeviceName(device.getName());
        // 冗余 host:port，便于同名设备在日志中区分（device 改名/删除后仍可读）
        log.setDeviceAddress(device.getAddress());
        log.setAction(powerOn ? ScreenLog.Action.ON : ScreenLog.Action.OFF);
        log.setTriggerType(trigger);
        // SCHEDULE 触发时记录来源 schedule；MANUAL / API 留 null
        log.setScheduleId(scheduleId);
        log.setScheduleName(scheduleName);
        log.setSuccess(success);
        if (message != null && message.length() > 990) {
            message = message.substring(0, 980) + "...";
        }
        log.setMessage(message);
        log.setDurationMs(durationMs);
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
        return controlAllExcept(powerOn, List.of(), trigger, null, null);
    }

    /**
     * 批量控制所有启用设备，但**排除**指定 device id 列表。
     * 用于实现 schedule 优先级：所有设备 mode 触发时，把"被单台 schedule 同 cron 接管"的设备排除掉。
     *
     * <p>例：所有设备 schedule "0 50 17 * * *" 触发时：
     * <ol>
     *   <li>查 {@code scheduleRepo.findOverridingDeviceIds("0 50 17 * * *")} 拿到被接管的设备 ID 列表</li>
     *   <li>这些设备由各自的单台 schedule 控制，不在本批次里</li>
     *   <li>其他设备走本方法批量控制</li>
     * </ol>
     *
     * @param powerOn    true=开屏, false=关屏
     * @param excludeIds 排除的 device id 列表（不修改原集合）
     * @param trigger    触发类型，写到 screen_log.trigger 字段
     */
    public BatchControlResult controlAllExcept(boolean powerOn, List<Long> excludeIds, ScreenLog.TriggerType trigger,
                                               Long scheduleId, String scheduleName) {
        // 转 Set 让 contains 是 O(1)
        Set<Long> excludeSet = excludeIds == null ? Set.of() : new HashSet<>(excludeIds);

        // 过滤掉禁用的和被排除的
        List<Device> devices = deviceRepo.findAllByEnabledTrue().stream()
                .filter(d -> !excludeSet.contains(d.getId()))
                .toList();

        if (devices.isEmpty()) {
            if (excludeSet.isEmpty()) {
                return new BatchControlResult(0, 0, List.of(), "没有启用的设备");
            } else {
                return new BatchControlResult(0, 0, List.of(),
                        "所有启用的设备都被单台定时器覆盖（" + excludeSet.size() + " 台），本批次无设备可执行");
            }
        }

        String action = powerOn ? "开屏" : "关屏";
        String skipNote = excludeSet.isEmpty() ? "" :
                "（已跳过 " + excludeSet.size() + " 台被单台定时器覆盖的设备）";
        log.info("批量{}开始，设备数={} {}", action, devices.size(), skipNote);

        // 并发提交到 batchPool。lambda 里调 self.control()，必须走代理才能触发 @Transactional
        List<CompletableFuture<DeviceResult>> futures = new ArrayList<>(devices.size());
        for (Device d : devices) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    String msg = self.control(d.getId(), powerOn, trigger, scheduleId, scheduleName);
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

        String summary = String.format("批量%s: %d/%d 成功%s", action, success, devices.size(), skipNote);
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
