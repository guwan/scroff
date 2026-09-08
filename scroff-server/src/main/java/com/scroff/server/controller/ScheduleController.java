package com.scroff.server.controller;

import com.scroff.server.entity.Schedule;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.repository.ScheduleRepository;
import com.scroff.server.scheduler.ScheduleExecutor;
import com.scroff.server.service.ScreenPowerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 定时任务管理页面。
 *
 * cron 字段使用 Spring 6 字段格式："秒 分 时 日 月 周"
 * 例如 "0 0 22 * * *" = 每天 22:00:00
 */
@Controller
@RequestMapping("/schedules")
@RequiredArgsConstructor
@Slf4j
public class ScheduleController {

    private final ScheduleRepository scheduleRepo;
    private final DeviceRepository deviceRepo;
    private final ScheduleExecutor executor;
    private final ScreenPowerService screenPowerService;

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        var pageResult = scheduleRepo.findAll(PageRequest.of(page, size, Sort.by("id").ascending()));
        model.addAttribute("page", pageResult);

        // 给每个 schedule 计算 deviceName 展示：所有设备模式 → "所有设备"；指定设备模式 → 用 getDeviceIdList() 拼名字
        Map<Long, String> deviceNamesForSchedule = new LinkedHashMap<>();
        var allDevices = deviceRepo.findAll();
        Map<Long, String> idToName = new LinkedHashMap<>();
        allDevices.forEach(d -> idToName.put(d.getId(), d.getName() + " (" + d.getAddress() + ")"));
        model.addAttribute("deviceMap", idToName);

        for (Schedule s : pageResult.getContent()) {
            if (s.isForAllDevices()) {
                deviceNamesForSchedule.put(s.getId(), "📺 所有设备");
            } else {
                List<String> names = new ArrayList<>();
                for (Long did : s.getDeviceIdList()) {
                    String nm = idToName.getOrDefault(did, "(已删除)");
                    names.add(nm);
                }
                deviceNamesForSchedule.put(s.getId(), String.join(", ", names));
            }
        }
        model.addAttribute("deviceNamesForSchedule", deviceNamesForSchedule);
        return "schedules";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new ScheduleForm());
        model.addAttribute("devices", deviceRepo.findAll());
        model.addAttribute("deviceMap", deviceMap());
        model.addAttribute("schedule", null);
        return "schedule-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") ScheduleForm form,
                         BindingResult br,
                         RedirectAttributes ra) {
        if (br.hasErrors()) {
            return "schedule-form";
        }
        Schedule s = new Schedule();
        applyForm(s, form);
        Schedule saved = scheduleRepo.save(s);
        if (saved.getEnabled()) executor.register(saved);
        ra.addFlashAttribute("msg", "定时任务已创建: " + saved.getName());
        return "redirect:/schedules";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<Schedule> opt = scheduleRepo.findById(id);
        if (opt.isEmpty()) {
            ra.addFlashAttribute("err", "定时任务不存在: id=" + id);
            return "redirect:/schedules";
        }
        Schedule s = opt.get();
        ScheduleForm f = new ScheduleForm();
        // 全部模式 → deviceIds 填空；指定设备模式 → 把 entity.getDeviceIdList() 序列化成逗号串
        List<Long> ids = s.getDeviceIdList();
        f.setDeviceIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        f.setTargetAll(s.getTargetAll());
        f.setName(s.getName());
        f.setAction(s.getAction());
        f.setTargetPath(s.getTargetPath());
        f.setCron(s.getCron());
        f.setEnabled(s.getEnabled());
        model.addAttribute("form", f);
        model.addAttribute("devices", deviceRepo.findAll());
        model.addAttribute("deviceMap", deviceMap());
        model.addAttribute("schedule", s);
        return "schedule-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") ScheduleForm form,
                         BindingResult br,
                         RedirectAttributes ra) {
        Optional<Schedule> opt = scheduleRepo.findById(id);
        if (opt.isEmpty()) {
            ra.addFlashAttribute("err", "定时任务不存在");
            return "redirect:/schedules";
        }
        if (br.hasErrors()) {
            return "schedule-form";
        }
        Schedule s = opt.get();
        applyForm(s, form);
        Schedule saved = scheduleRepo.save(s);
        executor.register(saved);
        ra.addFlashAttribute("msg", "定时任务已更新: " + saved.getName());
        return "redirect:/schedules";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        executor.unregister(id);
        scheduleRepo.deleteById(id);
        ra.addFlashAttribute("msg", "定时任务已删除: id=" + id);
        return "redirect:/schedules";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        Optional<Schedule> opt = scheduleRepo.findById(id);
        if (opt.isEmpty()) {
            ra.addFlashAttribute("err", "定时任务不存在");
            return "redirect:/schedules";
        }
        Schedule s = opt.get();
        s.setEnabled(!s.getEnabled());
        scheduleRepo.save(s);
        if (s.getEnabled()) executor.register(s);
        else executor.unregister(s.getId());
        ra.addFlashAttribute("msg", "已" + (s.getEnabled() ? "启用" : "禁用") + ": " + s.getName());
        return "redirect:/schedules";
    }

    /**
     * 立即手动运行一次定时任务（用于测试配置）。
     * 不管 enabled 状态都强制执行一次（执行时 runSchedule 内部也会再判 enabled，
     * 所以这里先临时把 enabled 设 true，跑完恢复原值）。
     */
    @PostMapping("/{id}/run")
    public String runNow(@PathVariable Long id, RedirectAttributes ra) {
        Optional<Schedule> opt = scheduleRepo.findById(id);
        if (opt.isEmpty()) {
            ra.addFlashAttribute("err", "定时任务不存在");
            return "redirect:/schedules";
        }
        Schedule s = opt.get();
        boolean wasEnabled = Boolean.TRUE.equals(s.getEnabled());
        try {
            if (!wasEnabled) {
                // 临时启用以便 runSchedule 能执行，跑完恢复
                s.setEnabled(true);
                scheduleRepo.saveAndFlush(s);
            }
            screenPowerService.runSchedule(id);
            // 重新读取一次拿到 runSchedule 回写后的 lastRunMessage
            Schedule after = scheduleRepo.findById(id).orElse(s);
            String summary = after.getLastRunMessage() != null ? after.getLastRunMessage() : "已触发";
            boolean success = after.getLastRunStatus() == Schedule.LastRunStatus.SUCCESS;
            if (success) {
                ra.addFlashAttribute("msg", "手动运行成功: " + s.getName() + " — " + summary);
            } else {
                ra.addFlashAttribute("err", "手动运行结果为失败: " + s.getName() + " — " + summary);
            }
        } catch (Exception e) {
            log.error("手动运行 schedule 异常", e);
            ra.addFlashAttribute("err", "手动运行异常: " + s.getName() + " — " + e.getMessage());
        } finally {
            if (!wasEnabled) {
                Schedule fresh = scheduleRepo.findById(id).orElse(s);
                fresh.setEnabled(false);
                scheduleRepo.save(fresh);
            }
        }
        return "redirect:/schedules";
    }

    private void applyForm(Schedule s, ScheduleForm f) {
        boolean all = Boolean.TRUE.equals(f.getTargetAll());
        s.setTargetAll(all);
        if (all) {
            // 全部模式：清空 deviceIds / deviceId
            s.setDeviceIds(null);
            s.setDeviceId(0L);
        } else {
            // 指定设备模式：把 form.deviceIds（逗号分隔字符串）解析成 List<Long> 再双向同步
            List<Long> ids = parseIds(f.getDeviceIds());
            s.setDeviceIdList(ids);
        }
        s.setName(f.getName());
        s.setAction(f.getAction());
        s.setTargetPath(f.getTargetPath());
        s.setCron(f.getCron());
        s.setEnabled(f.getEnabled() != null ? f.getEnabled() : Boolean.FALSE);
    }

    /** 把 "1,2,3" 解析成 [1, 2, 3]，空串/null 返回空列表。非法字符会跳过。 */
    private static List<Long> parseIds(String deviceIdsStr) {
        if (deviceIdsStr == null || deviceIdsStr.isBlank()) return List.of();
        return Arrays.stream(deviceIdsStr.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    private Map<Long, String> deviceMap() {
        Map<Long, String> m = new LinkedHashMap<>();
        deviceRepo.findAll().forEach(d -> m.put(d.getId(), d.getName() + " (" + d.getAddress() + ")"));
        return m;
    }

    /**
     * 定时任务表单 DTO
     */
    @Data
    public static class ScheduleForm {
        /**
         * 设备 ID 列表，逗号分隔字符串（如 "3,5,8"）。
         * 由前端多选控件提交；后端 parseIds() 解析成 List<Long>。
         */
        @Size(max = 2000)
        private String deviceIds;

        @NotBlank
        @Size(max = 100)
        private String name;

        @NotNull
        private Schedule.Action action;

        /** OPEN_FILE 时存 Android 文件路径，OPEN_APP 时存包名或 component；ON/OFF 时忽略 */
        @Size(max = 500)
        private String targetPath;

        /** 宽松校验：6 段空格分隔的 cron */
        @NotBlank
        @Pattern(regexp = "^\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+$",
                 message = "cron 必须为 6 段（秒 分 时 日 月 周），例：0 0 22 * * *")
        private String cron;

        private Boolean enabled = Boolean.TRUE;

        /** true=对所有启用设备生效, false=对指定多台设备生效（默认 false） */
        private Boolean targetAll = Boolean.FALSE;

        /**
         * 跨字段校验：指定设备模式下至少要选一台设备。
         */
        @AssertTrue(message = "指定设备模式下必须至少选择一台设备")
        public boolean isDeviceIdsValidForScope() {
            if (Boolean.TRUE.equals(targetAll)) return true;
            return deviceIds != null && !deviceIds.isBlank();
        }

        /**
         * 跨字段校验：OPEN_FILE / OPEN_APP 时 targetPath 不能空。
         */
        @AssertTrue(message = "打开文件/打开应用时必须指定目标路径")
        public boolean isTargetPathValidForAction() {
            if (action == null) return true;
            return switch (action) {
                case OPEN_FILE, OPEN_APP -> targetPath != null && !targetPath.isBlank();
                default -> true;
            };
        }
    }
}
