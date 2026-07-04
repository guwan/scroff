package com.scroff.server.controller;

import com.scroff.server.entity.Device;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.repository.ScheduleRepository;
import com.scroff.server.repository.ScreenLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首页 Dashboard。
 * 展示：设备总数 / 在线数 / 启用 schedule 数 / 最近 10 条执行日志
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DeviceRepository deviceRepo;
    private final ScheduleRepository scheduleRepo;
    private final ScreenLogRepository logRepo;

    @GetMapping("/")
    public String index(Model model) {
        Map<String, Long> deviceStats = new LinkedHashMap<>();
        deviceStats.put("total", deviceRepo.count());
        deviceStats.put("online", deviceRepo.countByStatus(Device.Status.ONLINE));
        deviceStats.put("offline", deviceRepo.countByStatus(Device.Status.OFFLINE));
        deviceStats.put("error", deviceRepo.countByStatus(Device.Status.ERROR));

        model.addAttribute("deviceStats", deviceStats);
        model.addAttribute("scheduleCount", scheduleRepo.countByEnabledTrue());
        model.addAttribute("recentLogs",
                logRepo.findAllByOrderByExecutedAtDesc(PageRequest.of(0, 10)));
        return "dashboard";
    }
}
