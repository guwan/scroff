package com.scroff.server.util;

import org.springframework.data.domain.Page;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分页组件的工具方法。
 * <p>
 * Controller 里一行就能准备好 fragment 要用的所有 model 属性：
 * <pre>
 *   PagerHelper.prepare(page, model.asMap(), "/devices",
 *       "name", name, "category", category, "size", size, ...);
 * </pre>
 */
public final class PagerHelper {

    private PagerHelper() {}

    /**
     * 把显示页码、省略号、分页 URL 等都算好，塞进 model。
     *
     * @param page         Spring Data Page
     * @param model        controller 的 Model.asMap()
     * @param baseUrl      列表页 URL 前缀，如 "/devices"
     * @param kv           过滤条件键值对（偶数位是 key，奇数位是 value），null/空字符串跳过。
     *                     注意：不要传 "page"，prepare 自己拼。
     */
    public static void prepare(Page<?> page, Map<String, Object> model, String baseUrl, Object... kv) {
        model.put("page", page);
        model.put("pagerBase", baseUrl);

        // 拼除了 page 之外的查询参数
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object key = kv[i];
            Object val = kv[i + 1];
            if (key == null || val == null) continue;
            String ks = key.toString();
            String vs = val.toString();
            if (vs.isEmpty()) continue;
            if ("page".equals(ks)) continue;
            params.put(ks, vs);
        }
        String qs = buildQs(params);
        model.put("pagerQs", qs);
        // 同时存一份 Map 结构，fragment 里跳页表单用 th:each 遍历
        model.put("pagerParams", params);

        int totalPages = page.getTotalPages();
        int current = page.getNumber();

        if (totalPages <= 1) {
            model.put("pagerDisplay", new ArrayList<Integer>());
            model.put("pagerShowLeft", false);
            model.put("pagerShowRight", false);
            // 单页时 URL 也不用算了
            return;
        }

        // 先算页码显示策略
        List<Integer> display = new ArrayList<>();
        boolean showLeft = false;
        boolean showRight = false;

        if (totalPages <= 7) {
            for (int i = 1; i < totalPages - 1; i++) display.add(i);
        } else {
            int start = Math.max(1, current - 1);
            int end   = Math.min(totalPages - 2, current + 1);
            if (end - start + 1 < 3) {
                if (start == 1)      end = Math.min(totalPages - 2, start + 2);
                else if (end == totalPages - 2) start = Math.max(1, end - 2);
            }
            for (int i = start; i <= end; i++) display.add(i);
            showLeft  = start > 1;
            showRight = end < totalPages - 2;
        }

        model.put("pagerDisplay", display);
        model.put("pagerShowLeft", showLeft);
        model.put("pagerShowRight", showRight);

        // 预算统计信息里的"当前记录范围（如 21-40）"，避免 Thymeleaf 做数学运算
        long totalElements = page.getTotalElements();
        int size = page.getSize();
        long startIdx = (long) current * size + 1;
        long endIdx = Math.min((long) (current + 1) * size, totalElements);
        model.put("pagerRangeText", startIdx + "-" + endIdx);

        // 预拼好所有分页 URL —— fragment 里直接拿字符串，零表达式运算
        model.put("firstUrl",  buildPageUrl(baseUrl, qs, 0));
        model.put("prevUrl",   buildPageUrl(baseUrl, qs, Math.max(0, current - 1)));
        model.put("nextUrl",   buildPageUrl(baseUrl, qs, Math.min(totalPages - 1, current + 1)));
        model.put("lastUrl",   buildPageUrl(baseUrl, qs, totalPages - 1));
        // 每个中间页码的 URL（包含首尾页，fragment 统一处理）
        Map<Integer, String> pageUrls = new LinkedHashMap<>();
        pageUrls.put(0, buildPageUrl(baseUrl, qs, 0));
        for (int p : display) pageUrls.put(p, buildPageUrl(baseUrl, qs, p));
        pageUrls.put(totalPages - 1, buildPageUrl(baseUrl, qs, totalPages - 1));
        model.put("pagerPageUrls", pageUrls);
    }

    /** 拼完整 URL：baseUrl + ?page=N + 其他过滤条件 */
    private static String buildPageUrl(String baseUrl, String qs, int pageIndex) {
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append("?page=").append(pageIndex);
        if (qs != null && !qs.isEmpty()) {
            sb.append('&').append(qs);
        }
        return sb.toString();
    }

    public static String buildQs(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(UriUtils.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(UriUtils.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }
}
