package com.scroff.server.scheduler;

import com.scroff.server.config.ScroffProperties;
import com.scroff.server.entity.Schedule;
import com.scroff.server.repository.ScheduleRepository;
import com.scroff.server.service.ScreenPowerService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 定时任务执行器。
 *
 * 启动时把数据库里所有 enabled 的 schedule 注册到 Spring TaskScheduler。
 * 增删改 schedule 后由 Controller 显式调用 register/unregister。
 *
 * 调度模型：
 *  - 一次性 schedule：next() 算出下次触发时间 → taskScheduler.schedule(runnable, Date)
 *  - 触发后回调里再次算 next()，循环注册自身
 *  - 这套模型不依赖 cron persistence，重启后从 cron 表达式自然续上
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleExecutor {

    private final TaskScheduler taskScheduler;
    private final ScheduleRepository scheduleRepo;
    private final ScreenPowerService screenPowerService;
    private final ScroffProperties props;

    /** scheduleId → 当前注册的任务句柄，便于取消 */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> registry = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (!props.getSchedule().isLoadOnStartup()) {
            log.info("schedule.load-on-startup=false，跳过加载");
            return;
        }
        // 等 5s 让 Spring 完全初始化（包括 DB 连接）
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            List<Schedule> all = scheduleRepo.findAllByEnabledTrue();
            log.info("启动加载 schedule: 共 {} 条 enabled", all.size());
            for (Schedule s : all) {
                try {
                    register(s);
                } catch (Exception e) {
                    log.error("注册 schedule 失败: id={}, cron={}", s.getId(), s.getCron(), e);
                }
            }
        } catch (Exception e) {
            log.error("启动加载 schedule 异常", e);
        }
    }

    /**
     * 注册 / 重新注册一个 schedule（idempotent）
     */
    public void register(Schedule s) {
        unregister(s.getId());
        CronExpression cron;
        try {
            cron = CronExpression.parse(s.getCron());
        } catch (Exception e) {
            log.error("schedule {} cron 解析失败: {}", s.getId(), s.getCron(), e);
            return;
        }
        LocalDateTime next = cron.next(LocalDateTime.now());
        if (next == null) {
            log.warn("schedule {} cron 已无下次触发: {}", s.getId(), s.getCron());
            return;
        }
        Date triggerAt = Date.from(next.atZone(ZoneId.systemDefault()).toInstant());
        log.info("注册 schedule id={}, name={}, cron='{}', next={}",
                s.getId(), s.getName(), s.getCron(), next);

        ScheduledFuture<?> f = taskScheduler.schedule(
                () -> {
                    try {
                        log.info("schedule 触发: id={}", s.getId());
                        screenPowerService.runSchedule(s.getId());
                    } catch (Exception e) {
                        log.error("schedule 执行异常: id=" + s.getId(), e);
                    } finally {
                        // 重新注册下一次
                        scheduleRepo.findById(s.getId()).ifPresent(this::register);
                    }
                },
                triggerAt
        );
        registry.put(s.getId(), f);
    }

    /**
     * 取消注册
     */
    public void unregister(Long scheduleId) {
        ScheduledFuture<?> f = registry.remove(scheduleId);
        if (f != null) {
            f.cancel(false);
            log.debug("schedule {} 已取消注册", scheduleId);
        }
    }

    /**
     * 重置全部（DB 内容变化较大时使用）
     */
    public void reloadAll() {
        registry.keySet().forEach(this::unregister);
        scheduleRepo.findAllByEnabledTrue().forEach(this::register);
    }

    /**
     * 距下次触发的时间（用于 UI 展示），失败返回 null
     */
    public Duration nextDelay(Long scheduleId) {
        try {
            Schedule s = scheduleRepo.findById(scheduleId).orElse(null);
            if (s == null) return null;
            CronExpression cron = CronExpression.parse(s.getCron());
            LocalDateTime next = cron.next(LocalDateTime.now());
            if (next == null) return null;
            return Duration.between(LocalDateTime.now(), next);
        } catch (Exception e) {
            return null;
        }
    }
}
