package org.offblink.reverie.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地选项匹配（SPEC §7，零额外请求）：
 *   ① 序号词：全文即单个序号（一/三/1/2…，normalize 去掉「选/第/个」后），
 *      或原文含「第X个」结构 → 下标
 *   ② 相似度：normalize（去选/第/个/吧/了/的/语气词/标点）后，
 *      选项包含整句关键词（≥2 字）→ 0.9；否则 bigram Dice 系数；≥0.5 取最高
 *   未命中返回 -1（视为无效响应，选项仍可点）
 * 纯函数、无 Android 依赖——可在宿主 JVM 直接 javac 验证。
 */
final class OptionMatcher {

    private OptionMatcher() { }

    /** 归一化：去口语填充与标点，只留实质词 */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("选第个吧了的吗呢啊呀呃嗯。！？!?，,、；;：:．. \t　".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 返回命中的选项下标；未命中 -1 */
    static int match(String heard, List<String> options) {
        if (heard == null || options == null || options.isEmpty()) return -1;
        String h = normalize(heard);
        if (h.isEmpty()) return -1;

        // ① 序号词
        Integer num = numberIndex(heard, h);
        if (num != null && num < options.size()) return num;

        // ② 归一化包含/相似度，≥0.5 取最高
        int best = -1;
        float bestScore = 0.5f;
        for (int i = 0; i < options.size(); i++) {
            String on = normalize(options.get(i));
            if (on.isEmpty()) continue;
            float s;
            if (h.length() >= 2 && on.contains(h)) s = 0.9f;   // 关键词命中
            else s = dice(h, on);
            if (s >= 0.5f && s > bestScore) { bestScore = s; best = i; }
        }
        return best;
    }

    /** 序号词 → 下标；不成序号返回 null */
    private static Integer numberIndex(String raw, String normalized) {
        final String cn = "一二三四五六七八九";
        // 全文即单个序号（"三"、"2"——normalize 已剥掉 选/第/个）
        if (normalized.length() == 1) {
            char c = normalized.charAt(0);
            if (c >= '1' && c <= '9') return c - '1';
            int k = cn.indexOf(c);
            if (k >= 0) return k;
        }
        // 原文含「第X个 / 第X」
        for (int i = 0; i < raw.length() - 1; i++) {
            if (raw.charAt(i) != '第') continue;
            char c = raw.charAt(i + 1);
            if (c >= '1' && c <= '9') return c - '1';
            int k = cn.indexOf(c);
            if (k >= 0) return k;
        }
        return null;
    }

    /** bigram Dice 系数（2|∩| / (|a|+|b|)，长度按 bigram 数） */
    private static float dice(String a, String b) {
        if (a.equals(b)) return 1f;
        if (a.length() < 2 || b.length() < 2) return 0f;
        Map<String, Integer> ga = bigrams(a);
        Map<String, Integer> gb = bigrams(b);
        int inter = 0;
        for (Map.Entry<String, Integer> e : ga.entrySet()) {
            Integer c = gb.get(e.getKey());
            if (c != null) inter += Math.min(c, e.getValue());
        }
        return 2f * inter / ((a.length() - 1) + (b.length() - 1));
    }

    private static Map<String, Integer> bigrams(String s) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < s.length() - 1; i++) {
            String g = s.substring(i, i + 2);
            Integer c = m.get(g);
            m.put(g, c == null ? 1 : c + 1);
        }
        return m;
    }
}
