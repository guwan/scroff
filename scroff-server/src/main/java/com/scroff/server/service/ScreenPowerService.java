package com.scroff.server.service;

import com.scroff.server.config.ScroffProperties;
import com.scroff.server.entity.Device;
import com.scroff.server.entity.Schedule;
import com.scroff.server.entity.ScreenLog;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.repository.ScheduleRepository;
import com.scroff.server.repository.ScreenLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

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
@RequiredArgsConstructor
public class ScreenPowerService {

    private final AdbService adbService;
    private final DeviceManager deviceManager;
    private final DeviceRepository deviceRepo;
    private final ScheduleRepository scheduleRepo;
    private final ScreenLogRepository logRepo;
    private final ScroffProperties props;

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
        String msg = control(s.getDeviceId(), powerOn, ScreenLog.TriggerType.SCHEDULE);
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
}
