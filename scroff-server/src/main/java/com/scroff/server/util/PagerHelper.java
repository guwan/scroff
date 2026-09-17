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
 *   PagerHelper.prepare(page, model, "/devices",
 *       "name", name, "category", category, "size", size, ...);
 * </pre>
 */
public final class PagerHelper {

    private PagerHelper() {}

    /**
     * 把显示页码、省略号、qs 等都算好，塞进 model。
     *
     * @param page         Spring Data Page
     * @param model        controller 的 Model（或 RedirectAttributes 的 addFlashAttribute 也行）
     * @param baseUrl      列表页 URL 前缀，如 "/devices"
     * @param kv           过滤条件键值对（偶数位是 key，奇数位是 value），null/空字符串跳过。
     *                     注意：不要传 "page"，fragment 自己拼。
     */
    public static void prepare(Page<?> page, Map<String, Object> model, String baseUrl, Object... kv) {
        model.put("page", page);
        model.put("pagerBase", baseUrl);

        // 拼 qs
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object key = kv[i];
            Object val = kv[i + 1];
            if (key == null || val == null) continue;
            String ks = key.toString();
            String vs = val.toString();
            if (vs.isEmpty()) continue;
            if ("page".equals(ks)) continue; // fragment 自己拼 page
            params.put(ks, vs);
        }
        model.put("pagerQs", buildQs(params));

        // 算 displayPages + 省略号
        int totalPages = page.getTotalPages();
        int current = page.getNumber();

        if (totalPages <= 1) {
            model.put("pagerDisplay", new ArrayList<Integer>());
            model.put("pagerShowLeft", false);
            model.put("pagerShowRight", false);
            return;
        }

        // 策略：显示首尾 + 当前页 ± 1，总共最多 2 + 3 + 2 = 7 个按钮（不含 « ‹ › »）
        // displayPages 只放中间部分（不含首尾），所以 totalPages <= 7 时全放
        List<Integer> display = new ArrayList<>();
        boolean showLeft = false;
        boolean showRight = false;

        if (totalPages <= 7) {
            // 全显示，除了首尾（因为 fragment 会单独渲染首尾）
            for (int i = 1; i < totalPages - 1; i++) display.add(i);
        } else {
            // 当前页 ± 1（限制在 [1, total-2] 之间）
            int start = Math.max(1, current - 1);
            int end   = Math.min(totalPages - 2, current + 1);
            // 若 start 往后挪了，后面的空位补到 3 个
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
