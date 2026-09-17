package com.scroff.server.controller;

import com.scroff.server.entity.ScreenLog;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.repository.ScreenLogRepository;
import com.scroff.server.util.PagerHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 日志查看页面。
 *
 * 搜索条件（互相独立，均为可选）：
 *   deviceId   设备下拉（精确）
 *   deviceName 设备名（模糊，匹配日志冗余的 device_name）
 *   action     动作 ON / OFF
 *   ip         设备地址 host:port（模糊）
 *   category   设备分类（模糊，按设备当前分类过滤）
 *   location   设备位置（模糊，按设备当前位置过滤）
 *   startTime  时间从（datetime-local 格式 yyyy-MM-ddTHH:mm）
 *   endTime    时间到
 */
@Controller
@RequestMapping("/logs")
@RequiredArgsConstructor
public class LogController {

    private final ScreenLogRepository logRepo;
    private final DeviceRepository deviceRepo;

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "30") int size,
                       @RequestParam(required = false) Long deviceId,
                       @RequestParam(required = false) String deviceName,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String ip,
                       @RequestParam(required = false) String category,
                       @RequestParam(required = false) String location,
                       @RequestParam(required = false) String startTime,
                       @RequestParam(required = false) String endTime,
                       Model model) {
        model.addAttribute("devices", deviceRepo.findAll());

        // 空白条件当 null（不过滤）；动作/时间非法值忽略
        String dn = trimToNull(deviceName);
        String ipN = trimToNull(ip);
        String c = trimToNull(category);
        String l = trimToNull(location);
        ScreenLog.Action act = parseAction(action);
        LocalDateTime from = parseDateTime(startTime);
        LocalDateTime to = parseDateTime(endTime);

        var resultPage = logRepo.search(
                deviceId, dn, act, ipN, c, l, from, to, PageRequest.of(page, size));
        PagerHelper.prepare(resultPage, model.asMap(), "/logs",
                "deviceId", deviceId, "deviceName", dn,
                "action", act != null ? act.name() : null,
                "ip", ipN, "category", c, "location", l,
                "startTime", startTime, "endTime", endTime,
                "size", size);

        // 回显搜索条件（分页链接也要带上）
        model.addAttribute("selectedDeviceId", deviceId);
        model.addAttribute("deviceName", dn);
        model.addAttribute("action", act != null ? act.name() : null);
        model.addAttribute("ip", ipN);
        model.addAttribute("category", c);
        model.addAttribute("location", l);
        model.addAttribute("fromStr", from != null ? from.toString() : null);
        model.addAttribute("toStr", to != null ? to.toString() : null);
        model.addAttribute("hasFilter", deviceId != null || dn != null || act != null
                || ipN != null || c != null || l != null || from != null || to != null);
        return "logs";
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 动作过滤：非法值忽略（不过滤） */
    private static ScreenLog.Action parseAction(String action) {
        if (action == null || action.isBlank()) return null;
        try {
            return ScreenLog.Action.valueOf(action.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 解析 datetime-local 提交的时间（yyyy-MM-ddTHH:mm[:ss]），非法值忽略 */
    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
