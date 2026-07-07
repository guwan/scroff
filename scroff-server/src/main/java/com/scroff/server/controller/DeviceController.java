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
 *   POST /devices/{id}/duplicate 复制（sortOrder+1，name 加 "(副本)"，host 冲突时自动递增最后一节 IP）
 *   POST /devices/{id}/test      测试连接（表单，页面刷新看结果）
 *   POST /devices/{id}/check-status  查询屏幕 ON/OFF（AJAX，返回 JSON）
 *   POST /devices/{id}/on        手动开屏
 *   POST /devices/{id}/off       手动关屏
 *   POST /devices/all/on         批量开屏（所有 enabled）
 *   POST /devices/all/off        批量关屏（所有 enabled）
 *   POST /devices/all/test       一键测连（所有 enabled，AJAX 返回 JSON）
 *   POST /devices/all/check-status 一键查屏（所有 enabled，AJAX 返回 JSON）
 */
@Controller
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceRepository deviceRepo;
    private final DeviceManager deviceManager;
    private final ScreenPowerService screenPowerService;

    /**
     * 可选的页面大小（在 {@link #PAGE_SIZE_OPTIONS} 范围内）。
     * 防止用户手改 URL 传 0 / -1 / 999999 之类的值把分页搞坏。
     */
    private static final java.util.Set<Integer> PAGE_SIZE_OPTIONS =
            java.util.Set.of(10, 20, 50, 100, 200);

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        // 越界 / 非法值兜底
        if (size < 1 || size > 500 || !PAGE_SIZE_OPTIONS.contains(size)) {
            size = 20;
        }
        if (page < 0) {
            page = 0;
        }
        // 暴露给页面：当前 size、可用选项（用于下拉框）
        model.addAttribute("currentSize", size);
        model.addAttribute("sizeOptions", java.util.List.of(10, 20, 50, 100, 200));
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

    /**
     * 复制设备：把源设备除 id/时间/状态外的字段拷过来作为新设备插入。
     * 排序自动 +1，新设备名加 "(副本)" 后缀。
     *
     * <p>典型场景：批量添加同网段叫号机，复制后自动递增最后一节 IP，
     * 端口保持不变。如果当前网段无空 IP，提示手动改 host。
     */
    @PostMapping("/{id}/duplicate")
    public String duplicate(@PathVariable Long id, RedirectAttributes ra) {
        Optional<Device> opt = deviceRepo.findById(id);
        if (opt.isEmpty()) {
            ra.addFlashAttribute("err", "源设备不存在: id=" + id);
            return "redirect:/devices";
        }
        Device src = opt.get();

        // 1) 先尝试用源 host:port；冲突则递增最后一节 IP 找下一个空位
        String targetHost = src.getHost();
        int targetPort = src.getAdbPort();
        String ipSource = "（同源 host:port）";

        if (deviceRepo.findByHostAndAdbPort(targetHost, targetPort).isPresent()) {
            String nextIp = findNextFreeIp(targetHost, targetPort);
            if (nextIp == null) {
                ra.addFlashAttribute("err",
                        "复制失败：host:port (" + targetHost + ":" + targetPort
                                + ") 已存在，且网段内无空 IP 可用（已扫到 .254），请手动改 host");
                return "redirect:/devices";
            }
            targetHost = nextIp;
            ipSource = "（自动递增 IP → " + nextIp + "）";
        }

        Device copy = new Device();
        copy.setName(src.getName() + " (副本)");
        copy.setHost(targetHost);
        copy.setAdbPort(targetPort);
        copy.setLocation(src.getLocation());
        copy.setCategory(src.getCategory());
        copy.setNotes(src.getNotes());
        copy.setSortOrder(src.getSortOrder() + 1);
        copy.setEnabled(src.getEnabled());
        // serial 留 null（每台设备的硬件序列号都不一样，复制没意义）
        // status / lastError / lastSeenAt 用 @Entity 里的默认值（OFFLINE / null / null）

        Device saved = deviceRepo.save(copy);
        ra.addFlashAttribute("msg",
                "已复制 [" + src.getName() + "] → 新设备 id=" + saved.getId()
                        + " " + ipSource + " (sortOrder=" + saved.getSortOrder() + ")");
        return "redirect:/devices";
    }

    /**
     * 在源 IP 之后（最后一段 +1 开始）扫描最多 50 个候选 IP，
     * 返回第一个未被占用的。返回 null 表示网段已满 / 非 IPv4 / 越界。
     *
     * <p>只扫 .1~.254（.255 视为广播地址，不分配给设备）。
     * 50 次上限防极端情况下扫太久；正常 1~3 次内就能找到。
     */
    private String findNextFreeIp(String baseIp, int port) {
        String[] parts = baseIp.split("\\.");
        if (parts.length != 4) {
            // 不是 IPv4（如 hostname），不动
            return null;
        }
        String prefix = parts[0] + "." + parts[1] + "." + parts[2] + ".";
        int baseLast;
        try {
            baseLast = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        // 起点：源 IP + 1
        for (int i = 1; i <= 50; i++) {
            int next = baseLast + i;
            if (next >= 255) {
                // 越界（.255 视为广播），停止扫描
                return null;
            }
            String candidate = prefix + next;
            if (deviceRepo.findByHostAndAdbPort(candidate, port).isEmpty()) {
                return candidate;
            }
        }
        return null;
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

    /**
     * 查询设备屏幕状态（只读，不控制）。返回 JSON 供前端 AJAX 用。
     *
     * <p>响应示例：
     * <pre>
     * {
     *   "ok": true,
     *   "deviceId": 5,
     *   "deviceName": "前台叫号机1",
     *   "online": true,
     *   "screenOn": true,            // null = 未知
     *   "message": "设备在线，屏幕 ON（开）",
     *   "rawOutput": "Display Power: state=ON"
     * }
     * </pre>
     */
    @PostMapping(value = "/{id}/check-status", produces = "application/json;charset=UTF-8")
    @ResponseBody
    public java.util.Map<String, Object> checkStatus(@PathVariable Long id) {
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        Optional<Device> opt = deviceRepo.findById(id);
        if (opt.isEmpty()) {
            resp.put("ok", false);
            resp.put("online", false);
            resp.put("screenOn", null);
            resp.put("message", "设备不存在: id=" + id);
            return resp;
        }
        DeviceManager.ScreenStatusResult r = deviceManager.checkScreenStatus(opt.get());
        resp.put("ok", r.online());
        resp.put("deviceId", r.deviceId());
        resp.put("deviceName", r.deviceName());
        resp.put("online", r.online());
        resp.put("screenOn", r.screenOn());
        resp.put("message", r.message());
        resp.put("rawOutput", r.rawOutput());
        return resp;
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

    /**
     * 全部开启：所有 enabled=true 的设备一起开屏（用于测试连接 + 批量控制）。
     * 并发执行，详见 {@link ScreenPowerService#controlAll}。
     *
     * <p>接受 {@code size} 参数，重定向时透传，保留用户选择的页面大小。
     */
    @PostMapping("/all/on")
    public String turnAllOn(@RequestParam(defaultValue = "20") int size, RedirectAttributes ra) {
        ScreenPowerService.BatchControlResult r =
                screenPowerService.controlAll(true, ScreenLog.TriggerType.MANUAL);
        ra.addFlashAttribute(r.allSuccess() ? "msg" : "err", r.summary());
        return "redirect:/devices?size=" + size;
    }

    /**
     * 全部关闭：所有 enabled=true 的设备一起关屏。
     */
    @PostMapping("/all/off")
    public String turnAllOff(@RequestParam(defaultValue = "20") int size, RedirectAttributes ra) {
        ScreenPowerService.BatchControlResult r =
                screenPowerService.controlAll(false, ScreenLog.TriggerType.MANUAL);
        ra.addFlashAttribute(r.allSuccess() ? "msg" : "err", r.summary());
        return "redirect:/devices?size=" + size;
    }

    /**
     * 一键测连：测试所有启用设备的连接状态。返回 JSON 给前端 AJAX 弹窗。
     */
    @PostMapping(value = "/all/test", produces = "application/json;charset=UTF-8")
    @ResponseBody
    public java.util.Map<String, Object> testAll() {
        DeviceManager.BatchTestResult r = deviceManager.testAllConnections();
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("total", r.total());
        resp.put("online", r.online());
        resp.put("offline", r.offline());
        resp.put("results", r.results());
        return resp;
    }

    /**
     * 一键查屏：查询所有启用设备的屏幕 ON/OFF 状态。返回 JSON 给前端 AJAX 弹窗。
     */
    @PostMapping(value = "/all/check-status", produces = "application/json;charset=UTF-8")
    @ResponseBody
    public java.util.Map<String, Object> checkAllStatus() {
        DeviceManager.BatchStatusResult r = deviceManager.checkAllScreenStatus();
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("total", r.total());
        resp.put("screenOnCount", r.screenOnCount());
        resp.put("screenOffCount", r.screenOffCount());
        resp.put("unknownCount", r.unknownCount());
        resp.put("offlineCount", r.offlineCount());
        resp.put("results", r.results());
        return resp;
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
