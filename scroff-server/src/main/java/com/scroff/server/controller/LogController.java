package com.scroff.server.controller;

import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.repository.ScreenLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 日志查看页面
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
                       Model model) {
        model.addAttribute("devices", deviceRepo.findAll());
        if (deviceId != null) {
            model.addAttribute("page", logRepo.findByDeviceIdOrderByExecutedAtDesc(
                    deviceId, PageRequest.of(page, size)));
            model.addAttribute("selectedDeviceId", deviceId);
        } else {
            model.addAttribute("page", logRepo.findAllByOrderByExecutedAtDesc(
                    PageRequest.of(page, size)));
            model.addAttribute("selectedDeviceId", null);
        }
        return "logs";
    }
}
