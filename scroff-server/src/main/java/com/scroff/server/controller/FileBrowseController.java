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
import java.util.concurrent.*;

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

    /**
     * 批量校验多台设备上是否存在指定的文件或应用。
     *
     * <p>请求体：
     * <pre>{
     *   "deviceIds": [1, 2, 3],
     *   "targetPath": "/sdcard/Movies/demo.mp4",
     *   "action": "OPEN_FILE"  // 或 "OPEN_APP"
     * }</pre>
     *
     * <p>响应：
     * <pre>{
     *   "ok": true,
     *   "results": [
     *     {"deviceId": 1, "deviceName": "叫号机-窗口1", "online": true, "exists": true, "detail": "ls 输出一行 /sdcard/Movies/demo.mp4"},
     *     {"deviceId": 2, "deviceName": "叫号机-窗口2", "online": false, "exists": false, "detail": "设备不在线"}
     *   ],
     *   "summary": "2/3 台设备通过校验"
     * }</pre>
     */
    @PostMapping("/validate-target")
    public ResponseEntity<Map<String, Object>> validateTarget(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        List<Number> idNumbers = (List<Number>) body.get("deviceIds");
        String targetPath = body.get("targetPath") != null ? body.get("targetPath").toString() : "";
        String action = body.get("action") != null ? body.get("action").toString().toUpperCase() : "";

        if (idNumbers == null || idNumbers.isEmpty()) {
            result.put("ok", false);
            result.put("error", "未指定任何设备");
            return ResponseEntity.badRequest().body(result);
        }
        if (targetPath.isBlank()) {
            result.put("ok", false);
            result.put("error", "targetPath 不能为空");
            return ResponseEntity.badRequest().body(result);
        }
        boolean isOpenApp = "OPEN_APP".equals(action);

        List<Long> ids = idNumbers.stream().map(Number::longValue).toList();
        Map<Long, Device> deviceMap = new HashMap<>();
        deviceRepo.findAllById(ids).forEach(d -> deviceMap.put(d.getId(), d));

        // 并发校验每台设备
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, ids.size()));
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>(ids.size());
        for (Long id : ids) {
            futures.add(CompletableFuture.supplyAsync(() -> checkOne(id, deviceMap.get(id), targetPath, isOpenApp), pool));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("校验等待超时", e);
        } finally {
            pool.shutdownNow();
        }

        List<Map<String, Object>> results = futures.stream()
                .<Map<String, Object>>map(f -> {
                    if (f.isDone()) return f.join();
                    Map<String, Object> timeoutMap = new LinkedHashMap<>();
                    timeoutMap.put("timeout", true);
                    return timeoutMap;
                })
                .toList();

        long pass = results.stream().filter(r -> Boolean.TRUE.equals(r.get("exists"))).count();
        String summary = String.format("%d/%d 台设备通过校验", pass, results.size());

        result.put("ok", true);
        result.put("summary", summary);
        result.put("results", results);
        return ResponseEntity.ok(result);
    }

    /**
     * 校验单台设备的文件/应用是否存在。返回一个结果字典。
     */
    private Map<String, Object> checkOne(Long id, Device device, String targetPath, boolean isOpenApp) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("deviceId", id);
        if (device == null) {
            r.put("deviceName", "(设备不存在)");
            r.put("online", false);
            r.put("exists", false);
            r.put("detail", "设备记录不存在");
            return r;
        }
        r.put("deviceName", device.getName());

        if (!adbService.isOnline(device.getAddress())) {
            r.put("online", false);
            r.put("exists", false);
            r.put("detail", "设备不在线");
            return r;
        }
        r.put("online", true);

        AdbResult ar;
        if (isOpenApp) {
            // 取包名部分：如果 target 是 component 格式 (pkg/.Activity)，取 pkg 部分
            String pkg = targetPath.contains("/") ? targetPath.substring(0, targetPath.indexOf('/')) : targetPath;
            ar = adbService.execShell(device.getAddress(), "pm path " + shellEscape(pkg));
            boolean exists = ar.isSuccess() && (ar.getStdout() != null && ar.getStdout().contains(pkg));
            r.put("exists", exists);
            r.put("detail", exists ? "应用已安装: " + ar.getStdout().trim() : ("未找到应用 " + pkg + ": " + ar.getErrorMessage()));
        } else {
            // 文件校验：用 ls 检查
            String safePath = shellEscape(targetPath);
            ar = adbService.execShell(device.getAddress(), "ls -la " + safePath);
            boolean exists = ar.isSuccess() && ar.getStdout() != null && !ar.getStdout().isBlank()
                    && !ar.getStdout().contains("No such file");
            r.put("exists", exists);
            r.put("detail", exists ? "文件存在" : ("文件不存在: " + ar.getErrorMessage()));
        }
        return r;
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
