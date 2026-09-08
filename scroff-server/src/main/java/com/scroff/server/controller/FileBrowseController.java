package com.scroff.server.controller;

import com.scroff.server.entity.Device;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.service.AdbResult;
import com.scroff.server.service.AdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

/**
 * REST API：浏览 Android 设备的文件系统和已安装应用。
 * <p>
 * - GET /api/devices/{id}/browse?path=/sdcard        → 列出目录内容
 * - GET /api/devices/{id}/packages                    → 列出已安装应用包名
 * - GET /api/devices/{id}/pkg/{package}/activities    → 列出应用内 Activity（可选，留作扩展）
 *
 * <p>实现依赖 adb shell 命令，输出在服务器端解析为结构化 JSON。
 */
@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class FileBrowseController {

    private final DeviceRepository deviceRepo;
    private final AdbService adbService;

    /**
     * 浏览设备上的目录。返回文件/目录列表。
     *
     * @param deviceId 设备 ID
     * @param path     目录路径，默认 /sdcard
     */
    @GetMapping("/{id}/browse")
    public ResponseEntity<Map<String, Object>> browse(
            @PathVariable("id") Long deviceId,
            @RequestParam(defaultValue = "/sdcard") String path) {

        Map<String, Object> result = new LinkedHashMap<>();
        Optional<Device> devOpt = deviceRepo.findById(deviceId);
        if (devOpt.isEmpty()) {
            result.put("ok", false);
            result.put("error", "设备不存在: id=" + deviceId);
            return ResponseEntity.status(404).body(result);
        }
        Device device = devOpt.get();

        if (!adbService.isOnline(device.getAddress())) {
            result.put("ok", false);
            result.put("error", "设备不在线: " + device.getName());
            return ResponseEntity.status(503).body(result);
        }

        // 构造 ls 命令。Android toybox ls 不太一致，兼容几种：
        //   1. ls -p → 目录名后面加 /，便于区分
        //   2. 如果失败，回退到纯 ls
        String dirPath = normalizePath(path);
        String cmd = "ls -p " + shellEscape(dirPath);
        AdbResult r = adbService.execShell(device.getAddress(), cmd);

        if (!r.isSuccess()) {
            // 尝试回退：可能 ls 不支持 -p
            cmd = "ls " + shellEscape(dirPath);
            r = adbService.execShell(device.getAddress(), cmd);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        if (r.isSuccess()) {
            entries = parseLsOutput(r.getStdout());
        }

        // 计算 parent 路径
        String parentPath = computeParent(dirPath);

        result.put("ok", r.isSuccess());
        result.put("path", dirPath);
        result.put("parentPath", parentPath);
        result.put("entries", entries);
        result.put("device", Map.of(
                "id", device.getId(),
                "name", device.getName(),
                "address", device.getAddress()));
        if (!r.isSuccess()) {
            result.put("error", r.getErrorMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 列出设备上已安装的应用包名。
     */
    @GetMapping("/{id}/packages")
    public ResponseEntity<Map<String, Object>> listPackages(@PathVariable("id") Long deviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<Device> devOpt = deviceRepo.findById(deviceId);
        if (devOpt.isEmpty()) {
            result.put("ok", false);
            result.put("error", "设备不存在: id=" + deviceId);
            return ResponseEntity.status(404).body(result);
        }
        Device device = devOpt.get();

        if (!adbService.isOnline(device.getAddress())) {
            result.put("ok", false);
            result.put("error", "设备不在线: " + device.getName());
            return ResponseEntity.status(503).body(result);
        }

        // 用 pm list packages -f 可以拿到 apk 路径，便于后面可能扩展
        AdbResult r = adbService.execShell(device.getAddress(), "pm list packages");
        List<Map<String, String>> packages = new ArrayList<>();
        if (r.isSuccess()) {
            for (String line : r.getStdout().split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("package:")) {
                    String pkg = line.substring("package:".length()).trim();
                    if (!pkg.isEmpty()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("package", pkg);
                        m.put("label", pkg); // 暂时用包名当 label，后续可扩展 queryLauncherActivities 拿真实 app label
                        packages.add(m);
                    }
                }
            }
        }

        result.put("ok", r.isSuccess());
        result.put("packages", packages);
        result.put("device", Map.of(
                "id", device.getId(),
                "name", device.getName(),
                "address", device.getAddress()));
        if (!r.isSuccess()) {
            result.put("error", r.getErrorMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 列出应用内可启动的 Activity。
     * 返回格式：{ component: "com.example.app/.MainActivity", label: "主界面" }
     */
    @GetMapping("/{id}/pkg/{package}/activities")
    public ResponseEntity<Map<String, Object>> listActivities(
            @PathVariable("id") Long deviceId,
            @PathVariable("package") String packageName) {

        Map<String, Object> result = new LinkedHashMap<>();
        Optional<Device> devOpt = deviceRepo.findById(deviceId);
        if (devOpt.isEmpty()) {
            result.put("ok", false);
            result.put("error", "设备不存在");
            return ResponseEntity.status(404).body(result);
        }
        Device device = devOpt.get();

        if (!adbService.isOnline(device.getAddress())) {
            result.put("ok", false);
            result.put("error", "设备不在线");
            return ResponseEntity.status(503).body(result);
        }

        // dumpsys package 输出量很大，用 grep 过滤 activity + android.intent.action.MAIN
        String cmd = "dumpsys package " + shellEscape(packageName)
                + " | grep -A 1 'android.intent.action.MAIN' | head -30";
        AdbResult r = adbService.execShell(device.getAddress(), cmd);

        List<Map<String, String>> activities = new ArrayList<>();
        if (r.isSuccess()) {
            // dumpsys 输出里 activity 的 component 格式通常是：
            //   android.name = com.example.app.MainActivity
            //   Priority: 0  Preferred: false
            // 更可靠的方式是用 cmd package resolve-activity 或用 am start 的 dry-run
            // 这里提供简化版：直接用 cmd package resolve-activity
        }

        // 换个更靠谱的方式：用 monkey 测试启动时需要的格式
        // 先返回包名本身作为默认"打开方式"，让前端直接用
        Map<String, String> defaultActivity = new LinkedHashMap<>();
        defaultActivity.put("component", packageName); // 仅包名即可，monkey -p pkg 1 能启动
        defaultActivity.put("label", packageName + "（用 monkey 启动）");
        activities.add(defaultActivity);

        // 尝试用 cmd package resolve-activity 获取 launcher activity
        String resolveCmd = "cmd package resolve-activity --brief " + shellEscape(packageName);
        AdbResult rr = adbService.execShell(device.getAddress(), resolveCmd);
        if (rr.isSuccess()) {
            for (String line : rr.getStdout().split("\\r?\\n")) {
                line = line.trim();
                // 输出形如 "com.example.app/.MainActivity" 或 "com.example.app/com.example.app.MainActivity"
                if (line.contains("/") && line.startsWith(packageName)) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("component", line);
                    m.put("label", line.substring(line.lastIndexOf('/') + 1));
                    activities.add(0, m); // 放到最前面
                    break;
                }
            }
        }

        result.put("ok", true);
        result.put("activities", activities);
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /**
     * 规范化 Android 文件路径。必须以 / 开头，去除多余的 //，去掉末尾 /（除非是根目录）。
     */
    private static String normalizePath(String p) {
        if (p == null || p.isBlank()) return "/sdcard";
        String s = p.trim();
        if (!s.startsWith("/")) s = "/" + s;
        s = s.replaceAll("/+", "/");
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 计算父目录路径。
     */
    private static String computeParent(String p) {
        if (p == null || p.equals("/")) return "/";
        int idx = p.lastIndexOf('/');
        if (idx <= 0) return "/";
        return p.substring(0, idx);
    }

    /**
     * 简单的 shell 转义。把路径里的特殊字符用单引号包起来。
     * Android 上 ls 命令路径一般不含空格/特殊字符，但防一手。
     */
    private static String shellEscape(String path) {
        if (path == null) return "''";
        // 如果不含空格、$、`、" 等，直接返回
        if (!path.chars().anyMatch(c -> c == ' ' || c == '$' || c == '`' || c == '"' || c == '\'')) {
            return path;
        }
        // 用单引号包裹，内部单引号转义
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * 解析 `ls -p` 或 `ls` 的输出。
     * <ul>
     *   <li>当输入是 ls -p 输出时，目录名以 / 结尾</li>
     *   <li>否则需要额外调 adb 来判断（此处先简化：默认全部当作文件，目录由前端点击后的行为自动识别）</li>
     * </ul>
     */
    private static List<Map<String, Object>> parseLsOutput(String stdout) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (stdout == null) return result;

        boolean hasSlashSuffix = false;
        for (String rawLine : stdout.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            // ls -p: 目录会以 / 结尾
            boolean isDir = line.endsWith("/");
            if (isDir) {
                hasSlashSuffix = true;
                line = line.substring(0, line.length() - 1); // 去掉 /
            }

            // 跳过 . 和 ..
            if (".".equals(line) || "..".equals(line)) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", line);
            entry.put("isDir", isDir);
            entry.put("size", null); // 简化：不给 size
            entry.put("extension", getExtension(line));
            result.add(entry);
        }

        // 如果 ls -p 没用（没有目录带 /），用简化策略：
        // 默认第一个条目里不带点的可能是目录，但这不可靠
        // 保持现状即可，前端点击时再请求看能否浏览
        return result;
    }

    private static String getExtension(String name) {
        if (name == null) return null;
        int idx = name.lastIndexOf('.');
        if (idx <= 0 || idx == name.length() - 1) return "";
        return name.substring(idx + 1).toLowerCase();
    }
}
