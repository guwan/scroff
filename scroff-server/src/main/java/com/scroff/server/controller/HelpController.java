package com.scroff.server.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 教程 / 帮助页面。
 *
 * <p>纯静态内容（无需 model 数据），给新用户一个"5 分钟上手"的引导。
 * 内容写在 {@code help.html} 里，跟代码分开维护方便产品改文案。
 */
@Controller
public class HelpController {

    /**
     * 教程首页。访问路径：{@code /help}
     */
    @GetMapping("/help")
    public String index() {
        return "help";
    }
}
