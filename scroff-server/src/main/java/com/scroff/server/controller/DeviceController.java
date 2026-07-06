package com.scroff.server.controller;

import com.scroff.server.entity.Device;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.service.DeviceManager;
import com.scroff.server.service.ScreenPowerService;
import com.scroff.server.entity.ScreenLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import java.util.Optional;

/**
 * 设备管理页面。
 *
 * 路由：
 *   GET  /devices                列表
 *   GET  /devices/new            新建表单
 *   POST /devices                新建提交
 *   GET  /devices/{id}/edit      编辑表单
 *   POST /devices/{id}           编辑提交
 *   POST /devices/{id}/delete    删除
 *   POST /devices/{id}/test      测试连接（AJAX/表单皆可）
 *   POST /devices/{id}/on        手动开屏
 *   POST /devices/{id}/off       手动关屏
 */
@Controller
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceRepository deviceRepo;
    private final DeviceManager deviceManager;
    private final ScreenPowerService screenPowerService;

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        model.addAttribute("page",
                deviceRepo.findAll(PageRequest.of(page, size,
                        Sort.by("sortOrder").ascending()
                            .and(Sort.by("id").ascending()))));
        return "devices";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("device", new Device());
        model.addAttribute("form", new DeviceForm());
        return "device-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") DeviceForm form,
                         BindingResult br,
                         RedirectAttributes ra) {
        if (br.hasErrors()) return "device-form";
        if (deviceRepo.findByHostAndAdbPort(form.getHost(), form.getAdbPort()).isPresent()) {
            br.rejectValue("host", "duplicate", "已存在同 host:port 的设备");
            return "device-form";
        }
        Device d = new Device();
        applyForm(d, form);
        deviceRepo.save(d);
        ra.addFlashAttribute("msg", "设备已创建: " + d.getName());
        return "redirect:/devices";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<Device> opt = deviceRepo.findById(id);
        if (opt.isEmpty()) {
            ra.addFlashAttribute("err", "设备不存在: id=" + id);
            return "redirect:/devices";
        }
        Device d = opt.get();
        DeviceForm f = new DeviceForm();
        f.setName(d.getName());
        f.setHost(d.getHost());
        f.setAdbPort(d.getAdbPort());
        f.setLocation(d.getLocation());
        f.setCategory(d.getCategory());
        f.setNotes(d.getNotes());
        f.setSortOrder(d.getSortOrder());
        f.setEnabled(d.getEnabled());
        model.addAttribute("device", d);
        model.addAttribute("form", f);
        return "device-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") DeviceForm form,
                         BindingResult br,
                         RedirectAttributes ra) {
        Optional<Device> opt = deviceRepo.findById(id);
        if (opt.isEmpty()) {
            ra.addFlashAttribute("err", "设备不存在");
            return "redirect:/devices";
        }
        if (br.hasErrors()) return "device-form";
        Device d = opt.get();
        applyForm(d, form);
        deviceRepo.save(d);
        ra.addFlashAttribute("msg", "设备已更新: " + d.getName());
        return "redirect:/devices";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        deviceRepo.deleteById(id);
        ra.addFlashAttribute("msg", "设备已删除: id=" + id);
        return "redirect:/devices";
    }

    @PostMapping("/{id}/test")
    public String test(@PathVariable Long id, RedirectAttributes ra) {
        deviceRepo.findById(id).ifPresent(d -> {
            deviceManager.testConnection(d);
            Device fresh = deviceRepo.findById(id).orElse(d);
            String msg = "测试连接 [" + fresh.getName() + "] → " + fresh.getStatus();
            String err = fresh.getLastError();
            boolean ok = fresh.getStatus() == Device.Status.ONLINE;
            if (!ok && err != null && !err.isBlank()) {
                msg = msg + " | " + err;
            }
            ra.addFlashAttribute(ok ? "msg" : "err", msg);
        });
        return "redirect:/devices";
    }

    @PostMapping("/{id}/on")
    public String turnOn(@PathVariable Long id, RedirectAttributes ra) {
        String msg = screenPowerService.control(id, true, ScreenLog.TriggerType.MANUAL);
        boolean ok = !msg.contains("失败");
        ra.addFlashAttribute(ok ? "msg" : "err", "开屏: " + msg);
        return "redirect:/devices";
    }

    @PostMapping("/{id}/off")
    public String turnOff(@PathVariable Long id, RedirectAttributes ra) {
        String msg = screenPowerService.control(id, false, ScreenLog.TriggerType.MANUAL);
        boolean ok = !msg.contains("失败");
        ra.addFlashAttribute(ok ? "msg" : "err", "关屏: " + msg);
        return "redirect:/devices";
    }

    private void applyForm(Device d, DeviceForm f) {
        d.setName(f.getName());
        d.setHost(f.getHost());
        d.setAdbPort(f.getAdbPort());
        d.setLocation(f.getLocation());
        d.setCategory(f.getCategory());
        d.setNotes(f.getNotes());
        d.setSortOrder(f.getSortOrder() != null ? f.getSortOrder() : 0);
        d.setEnabled(f.getEnabled() != null ? f.getEnabled() : Boolean.TRUE);
    }

    /**
     * 设备表单 DTO（与 Entity 分离便于校验）
     */
    @Data
    public static class DeviceForm {
        @NotBlank
        @Size(max = 100)
        private String name;

        @NotBlank
        @Size(max = 64)
        private String host;

        @NotNull
        @Min(1) @Max(65535)
        private Integer adbPort = 5555;

        @Size(max = 200)
        private String location;

        @Size(max = 50)
        private String category;

        @Size(max = 2000)
        private String notes;

        @Min(0) @Max(Integer.MAX_VALUE)
        private Integer sortOrder = 0;

        private Boolean enabled = Boolean.TRUE;
    }
}
