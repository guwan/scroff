package com.scroff.server.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ADB 命令执行结果。
 */
@Getter
@RequiredArgsConstructor
public class AdbResult {

    /** 进程退出码；-1 表示进程启动失败或超时被强杀 */
    private final int exitCode;
    /** 标准输出（trim 后） */
    private final String stdout;
    /** 标准错误（trim 后） */
    private final String stderr;
    /** 是否成功：exitCode == 0 且无异常 */
    private final boolean success;
    /** 失败时的可读信息，便于排错 */
    private final String errorMessage;

    public static AdbResult ok(String stdout, String stderr) {
        return new AdbResult(0, stdout, stderr, true, null);
    }

    public static AdbResult fail(int code, String stdout, String stderr, String msg) {
        return new AdbResult(code, stdout, stderr, false, msg);
    }

    /** 取 stdout 的单行结果（adb devices 第一行通常是 List of devices attached） */
    public String firstLine() {
        if (stdout == null) return "";
        int idx = stdout.indexOf('\n');
        return idx < 0 ? stdout : stdout.substring(0, idx);
    }
}
