package com.scroff.server.service;

import com.scroff.server.config.ScroffProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * ADB 进程封装服务。
 *
 * 负责：
 * 1. 异步执行 adb 命令（带超时）
 * 2. 解析 adb devices 输出
 * 3. 不维护长连接（每次命令都是短进程），避免僵尸进程
 *
 * 线程模型：
 *  - 公共线程池 defaultPool 处理所有 adb 调用
 *  - 每条命令超时由 commandTimeout 控制，超时则 destroyForcibly
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdbService {

    private final ScroffProperties props;

    /** adb 子进程专用线程池（与 Tomcat 线程池隔离） */
    private ExecutorService adbPool;

    @PostConstruct
    void init() {
        // 池大小：CPU 核数 * 4，最多 16，最少 4
        int cores = Runtime.getRuntime().availableProcessors();
        int poolSize = Math.min(16, Math.max(4, cores * 4));
        adbPool = new ThreadPoolExecutor(
                poolSize, poolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                r -> {
                    Thread t = new Thread(r, "adb-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("AdbService 初始化完成，池大小={}, adb={}, timeout={}ms",
                poolSize, props.getAdb().getExecutable(), props.getAdb().getCommandTimeout());
    }

    // ------------------------------------------------------------------
    // 公共 API
    // ------------------------------------------------------------------

    /**
     * 异步执行 adb 命令。返回 Future<AdbResult>。
     *
     * @param args 完整命令参数（不含 adb 自身路径），例如 ["devices"]
     */
    public CompletableFuture<AdbResult> execAsync(String... args) {
        return CompletableFuture.supplyAsync(() -> exec(args), adbPool);
    }

    /**
     * 同步执行 adb 命令。会被调用方所在线程阻塞最长 commandTimeout 毫秒。
     */
    public AdbResult exec(String... args) {
        File exe = new File(props.getAdb().getExecutable());
        if (!exe.canExecute()) {
            String msg = "adb 不可执行: " + exe.getAbsolutePath();
            log.error(msg);
            return AdbResult.fail(-1, "", "", msg);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getAdb().getExecutable());
        for (String a : args) cmd.add(a);

        Process process = null;
        try {
            log.debug("执行 adb: {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectErrorStream(false);
            process = pb.start();
            final Process p = process;  // lambda 需要 final

            // 异步读取 stdout / stderr，避免管道阻塞导致进程挂死
            Future<String> outF = adbPool.submit(() -> readToString(p.getInputStream()));
            Future<String> errF = adbPool.submit(() -> readToString(p.getErrorStream()));

            boolean finished = p.waitFor(props.getAdb().getCommandTimeout(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                outF.cancel(true);
                errF.cancel(true);
                return AdbResult.fail(-1, "", "", "adb 命令超时，已强杀");
            }
            String out = outF.get(2, TimeUnit.SECONDS);
            String err = errF.get(2, TimeUnit.SECONDS);
            int code = p.exitValue();
            if (code == 0) {
                return AdbResult.ok(out, err);
            } else {
                return AdbResult.fail(code, out, err,
                        "adb 退出码 " + code + ": " + (err.isEmpty() ? out : err));
            }
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            log.error("adb 执行异常: {}", String.join(" ", cmd), e);
            return AdbResult.fail(-1, "", "", "异常: " + e.getMessage());
        }
    }

    /**
     * 连接到指定 host:port。
     * adb connect 本身幂等，重复调用安全。
     */
    public AdbResult connect(String host, int port) {
        return exec("connect", host + ":" + port);
    }

    /**
     * 断开指定连接。
     */
    public AdbResult disconnect(String host, int port) {
        return exec("disconnect", host + ":" + port);
    }

    /**
     * 在指定 device 上执行 shell 命令。
     *
     * 注意：使用单参数 shell 形式，命令字符串作为整体传入，规避空格/特殊字符注入。
     */
    public AdbResult execShell(String address, String shellCommand) {
        return exec("-s", address, "shell", shellCommand);
    }

    /**
     * 列出当前所有 adb 设备（含未授权的）。
     * 输出形如：
     *   List of devices attached
     *   192.168.1.100:5555    device
     *   192.168.1.101:5555    unauthorized
     */
    public List<String> listConnected() {
        AdbResult r = exec("devices");
        if (!r.isSuccess()) {
            log.warn("adb devices 失败: {}", r.getErrorMessage());
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : r.getStdout().split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices")) continue;
            // 第二列是状态
            String[] parts = line.split("\\s+");
            if (parts.length >= 2 && "device".equals(parts[1])) {
                result.add(parts[0]);
            }
        }
        return result;
    }

    /**
     * 检测指定 address 是否在当前连接列表中且状态为 device。
     */
    public boolean isOnline(String address) {
        return listConnected().contains(address);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private String readToString(java.io.InputStream is) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
        // 去除末尾换行
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\n') sb.setLength(len - 1);
        return sb.toString();
    }
}
