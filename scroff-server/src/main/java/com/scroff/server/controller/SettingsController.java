package com.scroff.server.controller;

import com.scroff.server.config.ScroffProperties.AdbProfile;
import com.scroff.server.service.AdbService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.util.List;

/**
 * 系统设置页：ADB profile 管理。
 *
 * 设计：
 *  - GET  /settings          展示所有 profile + 当前激活项
 *  - POST /settings/activate?id=xxx  切换激活
 *  - POST /settings/reset           清除 DB 覆盖，回到 yaml 默认
 */
@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final AdbService adbService;

    @GetMapping
    public String page(Model model) {
        List<AdbProfile> profiles = adbService.getAllProfiles();
        AdbProfile active = adbService.resolveActiveProfile();
        model.addAttribute("profiles", profiles);
        model.addAttribute("active", active);
        // 给模板一个"路径是否可执行"的标志，省得用户在 UI 自己想办法测
        model.addAttribute("exists", profiles.stream()
                .map(p -> {
                    boolean ok;
                    String exe = p.getExecutable();
                    if (exe == null || exe.isBlank()) {
                        ok = false;
                    } else {
                        try {
                            ok = new File(exe).canExecute();
                        } catch (Exception e) {
                            ok = false;
                        }
                    }
                    return java.util.Map.of("profile", p, "exists", ok);
                })
                .toList());
        return "settings";
    }

    @PostMapping("/activate")
    public String activate(@RequestParam String id, RedirectAttributes ra) {
        AdbProfile p = adbService.setActiveProfile(id);
        if (p == null) {
            ra.addFlashAttribute("err", "切换失败：profile '" + id + "' 在 application.yml 里没找到");
        } else {
            ra.addFlashAttribute("msg", "已切换 ADB profile: " + p.getId() + " (" + p.getName() + ")");
        }
        return "redirect:/settings";
    }

    @PostMapping("/reset")
    public String reset(RedirectAttributes ra) {
        adbService.clearActiveProfileOverride();
        AdbProfile p = adbService.resolveActiveProfile();
        ra.addFlashAttribute("msg", "已恢复 application.yml 默认: " + (p != null ? p.getId() : "<none>"));
        return "redirect:/settings";
    }
}
