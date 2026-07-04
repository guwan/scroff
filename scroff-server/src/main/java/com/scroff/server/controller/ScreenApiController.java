package com.scroff.server.controller;

import com.scroff.server.entity.ScreenLog;
import com.scroff.server.repository.ScreenLogRepository;
import com.scroff.server.service.ScreenPowerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API：
 *   POST /api/devices/{id}/screen/on
 *   POST /api/devices/{id}/screen/off
 *   GET  /api/devices
 *   GET  /api/devices/{id}/logs
 *
 * 给外部系统（手机 App / 第三方调度）调用。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScreenApiController {

    private final ScreenPowerService screenPowerService;
    private final ScreenLogRepository logRepo;

    @PostMapping("/devices/{id}/screen/on")
    public ResponseEntity<Map<String, Object>> turnOn(@PathVariable Long id) {
        return doControl(id, true);
    }

    @PostMapping("/devices/{id}/screen/off")
    public ResponseEntity<Map<String, Object>> turnOff(@PathVariable Long id) {
        return doControl(id, false);
    }

    private ResponseEntity<Map<String, Object>> doControl(Long id, boolean on) {
        String msg = screenPowerService.control(id, on, ScreenLog.TriggerType.API);
        boolean ok = !msg.contains("失败") && !msg.contains("不存在") && !msg.contains("不在线");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        body.put("message", msg);
        body.put("deviceId", id);
        body.put("action", on ? "ON" : "OFF");
        return ok ? ResponseEntity.ok(body) : ResponseEntity.status(500).body(body);
    }

    @GetMapping("/devices/{id}/logs")
    public ResponseEntity<?> recentLogs(@PathVariable Long id,
                                        @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                logRepo.findByDeviceIdOrderByExecutedAtDesc(id, PageRequest.of(0, size)));
    }
}
