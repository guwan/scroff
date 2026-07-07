package com.scroff.server.controller;

import com.scroff.server.entity.Schedule;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.repository.ScheduleRepository;
import com.scroff.server.scheduler.ScheduleExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 定时任务管理页面。
 *
 * cron 字段使用 Spring 6 字段格式："秒 分 时 日 月 周"
 * 例如 "0 0 22 * * *" = 每天 22:00:00
 */
@Controller
@RequestMapping("/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleRepository scheduleRepo;
    private final DeviceRepository deviceRepo;
    private final ScheduleExecutor executor;

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        model.addAttribute("page",
                scheduleRepo.findAll(PageRequest.of(page, size, Sort.by("id").ascending())));
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
        // 全部模式下 deviceId 填 0 占位，这里还原成 null 避免误导用户
        f.setDeviceId(s.isForAllDevices() ? null : s.getDeviceId());
        f.setTargetAll(s.getTargetAll());
        f.setName(s.getName());
        f.setAction(s.getAction());
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

    private void applyForm(Schedule s, ScheduleForm f) {
        // ⚠ 关键：之前漏了 setTargetAll，导致 form 提交什么值都不会生效
        // 表现：用户选"所有设备"保存后，列表仍显示"单台"+(已删除)；
        //       编辑后再点编辑，targetAll 永远不保留。
        boolean all = Boolean.TRUE.equals(f.getTargetAll());
        s.setTargetAll(all);
        // 全部模式下 deviceId 存 0（DB NOT NULL 约束兜底），executor 通过 isForAllDevices() 判定
        // 单台模式下用 form 提交的 deviceId（可能是 null，但 @AssertTrue 会拦截"单台模式忘选设备"）
        s.setDeviceId(all ? 0L : f.getDeviceId());
        s.setName(f.getName());
        s.setAction(f.getAction());
        s.setCron(f.getCron());
        // ⚠ 关键：checkbox 不勾选时 form 不提交该字段，Spring 会把 Boolean 字段绑为 null
        // 之前 `null → TRUE` 的兜底是 bug：用户取消启用也会被强制启用
        // 正确兜底是 null → FALSE（用户的"不勾选"明确意图 = 禁用）
        s.setEnabled(f.getEnabled() != null ? f.getEnabled() : Boolean.FALSE);
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
        /** 单台模式必填；全部模式忽略（executor 走 controlAll） */
        private Long deviceId;

        @NotBlank
        @Size(max = 100)
        private String name;

        @NotNull
        private Schedule.Action action;

        /** 宽松校验：6 段空格分隔的 cron */
        @NotBlank
        @Pattern(regexp = "^\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+$",
                 message = "cron 必须为 6 段（秒 分 时 日 月 周），例：0 0 22 * * *")
        private String cron;

        private Boolean enabled = Boolean.TRUE;

        /** true=对所有设备生效, false=对单台设备生效（默认 false） */
        private Boolean targetAll = Boolean.FALSE;

        /**
         * 跨字段校验：单台模式必须选设备。
         * 用 @AssertTrue 是 Bean Validation 标准的跨字段写法。
         */
        @AssertTrue(message = "单台模式下必须选择设备")
        public boolean isDeviceIdValidForScope() {
            return Boolean.TRUE.equals(targetAll) || deviceId != null;
        }
    }
}
