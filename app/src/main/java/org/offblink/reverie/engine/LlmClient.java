package org.offblink.reverie.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAI 兼容 SSE 流式 chat/completions（SPEC §5）。
 * 边收边把 segment 字段增量解码、按 。！？!?… 切句回调 —— 第 1 句立刻可送 TTS。
 */
public class LlmClient {

    public interface Listener {
        void onSentence(String sentence);      // segment 里新完成的一句（已去首尾空白）
        void onComplete(Result result);        // 整段 JSON 解析完成
        void onError(String msg);              // 网络/协议/解析失败
    }

    public static class Result {
        public String title = "";
        public String segment = "";
        public List<String> options = new ArrayList<>();
        public boolean isFinal = false;
        public String synopsis = "";
    }

    private static final MediaType JSON_T = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    /**
     * BASE URL 归一化（用户输入容错，M8）：
     * - 去首尾空白/控制字符（手机粘贴常带换行，曾污染 Authorization 头）
     * - 少写协议 → 补 https://
     * - 粘了完整接口路径（/chat/completions 等）→ 去掉，只留 base
     * - 去尾斜杠
     * 不猜 /v1：有的网关没有该层，猜错反而制造新 404；填错路径由报错信息自证。
     */
    public static String normalizeBase(String raw) {
        if (raw == null) return "";
        // 全角空格 U+3000、BOM U+FEFF：手机输入法/剪贴板常见夹带
        String u = raw.replace("\u3000", " ").replace("\uFEFF", "")
                .replaceAll("[\\r\\n\\t]", "").trim();
        if (u.isEmpty()) return "";
        if (!u.contains("://")) u = "https://" + u;
        final String[] tails = {"/chat/completions", "/audio/transcriptions", "/completions"};
        while (true) {
            String lower = u.toLowerCase(java.util.Locale.ROOT);
            String hit = null;
            for (String t : tails) {
                if (lower.endsWith(t)) { hit = t; break; }
            }
            if (hit == null) break;
            u = u.substring(0, u.length() - hit.length());
        }
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** HTTP 错误统一口径：带上真实请求 URL，响应体为空时明说并指向最常见原因 */
    public static String httpError(int code, String url, String body) {
        String b = trim(body, 300);
        if (b.isEmpty()) {
            return "HTTP " + code + " POST " + url
                    + "（响应体为空）—— 检查 BASE URL 路径：应填到 /v1 为止，勿重复 /chat/completions";
        }
        return "HTTP " + code + " POST " + url + ": " + b;
    }

    /** 异步流式请求；回调在 OkHttp 线程，调用方自己切线程 */
    public Call stream(String baseUrl, String apiKey, String model,
                       List<String[]> messages,   // [role, content]
                       Listener l) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("stream", true);
            JSONArray arr = new JSONArray();
            for (String[] m : messages) {
                JSONObject mm = new JSONObject();
                mm.put("role", m[0]);
                mm.put("content", m[1]);
                arr.put(mm);
            }
            body.put("messages", arr);
        } catch (Exception e) {
            throw new IOException("build body: " + e);
        }

        String url = normalizeBase(baseUrl) + "/chat/completions";
        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON_T));
        if (!apiKey.isEmpty()) rb.header("Authorization", "Bearer " + apiKey);

        Call call = http.newCall(rb.build());
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call c, IOException e) {
                if (!c.isCanceled()) l.onError("网络失败 POST " + url + ": " + e.getMessage());
            }

            @Override public void onResponse(Call c, Response resp) {
                try (Response r = resp) {
                    if (!r.isSuccessful()) {
                        String err = r.body() != null ? r.body().string() : "";
                        l.onError(httpError(r.code(), url, err));
                        return;
                    }
                    if (r.body() == null || r.body().source() == null) {
                        l.onError("空响应体");
                        return;
                    }
                    readSse(r, l);
                } catch (Exception e) {
                    l.onError("读流失败: " + e.getMessage());
                }
            }
        });
        return call;
    }

    /** 连通性探测：不带 stream 拉一个 1-token 补全 */
    public long test(String baseUrl, String apiKey, String model) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 1);
        JSONArray arr = new JSONArray();
        JSONObject mm = new JSONObject();
        mm.put("role", "user");
        mm.put("content", "hi");
        arr.put(mm);
        body.put("messages", arr);
        long t0 = System.currentTimeMillis();
        String url = normalizeBase(baseUrl) + "/chat/completions";
        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON_T));
        if (!apiKey.isEmpty()) rb.header("Authorization", "Bearer " + apiKey);
        try (Response r = http.newCall(rb.build()).execute()) {
            if (!r.isSuccessful()) {
                String err = r.body() != null ? r.body().string() : "";
                throw new IOException(httpError(r.code(), url, err));
            }
            return System.currentTimeMillis() - t0;
        }
    }

    /* ---------- SSE 读取 + 增量切句 ---------- */

    private void readSse(Response r, Listener l) throws IOException {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(r.body().byteStream(), java.nio.charset.StandardCharsets.UTF_8));
        SegmentEmitter emitter = new SegmentEmitter(l);
        StringBuilder raw = new StringBuilder();   // 模型完整输出（解析整段 JSON 用）
        String line;
        boolean sawDone = false;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            if ("[DONE]".equals(data)) { sawDone = true; break; }
            try {
                JSONObject o = new JSONObject(data);
                if (o.has("error")) {
                    l.onError("模型侧错误: " + trim(o.optString("error"), 200));
                    return;
                }
                JSONArray choices = o.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                String delta = choices.getJSONObject(0).optJSONObject("delta") != null
                        ? choices.getJSONObject(0).getJSONObject("delta").optString("content", "")
                        : "";
                if (delta.isEmpty()) continue;
                raw.append(delta);
                emitter.feed(delta);
            } catch (Exception e) {
                // 单条 data 坏了不杀整流
            }
        }
        emitter.finish();
        if (!sawDone && raw.length() == 0) { l.onError("流中断（无数据）"); return; }
        Result res = parseFull(raw.toString());
        if (res == null || res.segment.isEmpty()) { l.onError("JSON 解析失败: " + trim(raw.toString(), 200)); return; }
        l.onComplete(res);
    }

    /** 完整输出 → Result；容忍 ```json 围栏与前后杂文字，取首个 {…} 块 */
    static Result parseFull(String raw) {
        String s = raw.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int nl = s.indexOf('\n', fence);
            int end = s.indexOf("```", nl + 1);
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).trim();
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            Result res = new Result();
            res.title = o.optString("title", "");
            res.segment = o.optString("segment", "");
            res.isFinal = o.optBoolean("is_final", false);
            res.synopsis = o.optString("synopsis", "");
            JSONArray opts = o.optJSONArray("options");
            if (opts != null) for (int i = 0; i < opts.length(); i++) res.options.add(opts.getString(i));
            if (res.isFinal) res.options.clear();
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /**
     * 增量提取 "segment" 字段值并切句。
     * 状态机扫全量 delta 流：等 key → 等冒号 → 等引号 → 字符串内逐字符入 pend，
     * 遇句末标点吐一句。契约：JSON 模板里 segment 必须第一个字段（system 已约束）。
     */
    static class SegmentEmitter {
        private final Listener l;
        private final StringBuilder pend = new StringBuilder();  // 当前未完句（唯一缓冲）
        private int state = 0;   // 0/1 等 key / 2 等冒号 / 3 等左引号 / 4 字符串内 / 5 结束
        private int ki = 0;      // key 匹配位置
        private String esc = ""; // 挂起的转义（unicode 转义可能跨 chunk）

        SegmentEmitter(Listener l) { this.l = l; }

        void feed(String delta) {
            if (state == 5) return;
            for (int i = 0; i < delta.length(); i++) {
                char c = delta.charAt(i);
                switch (state) {
                    case 0: case 1: {
                        String key = "\"segment\"";
                        if (c == key.charAt(ki)) {
                            ki++;
                            state = (ki == key.length()) ? 2 : 1;
                        } else {
                            ki = (c == '"') ? 1 : 0;
                            state = (ki == 1) ? 1 : 0;
                        }
                        break;
                    }
                    case 2: if (c == ':') state = 3; break;
                    case 3: if (c == '"') state = 4; else if (c == '{' || c == '[') state = 5; break;
                    case 4: {
                        if (!esc.isEmpty()) { esc += c; resolveEscape(); break; }
                        if (c == '\\') { esc = "\\"; break; }
                        if (c == '"') { state = 5; flush(); return; }   // 尾句无标点也吐出
                        accept(c);
                        break;
                    }
                    default: break;
                }
            }
        }

        /** 转义解码出的字符直接入缓冲；unicode 转义未收齐继续攒 */
        private boolean resolveEscape() {
            char k = esc.charAt(1);
            if (k == 'u') {
                if (esc.length() < 6) return false;
                try { pend.append((char) Integer.parseInt(esc.substring(2), 16)); } catch (Exception e) { }
            } else {
                switch (k) {
                    case 'n': pend.append('\n'); break;
                    case 't': pend.append('\t'); break;
                    case 'r': pend.append('\r'); break;
                    default: pend.append(k); break;   // \" \\ \/
                }
            }
            esc = "";
            return true;
        }

        private void accept(char c) {
            pend.append(c);
            if ("。！？!?…".indexOf(c) >= 0) flush();
        }

        private void flush() {
            String s = pend.toString().trim();
            pend.setLength(0);
            if (!s.isEmpty()) l.onSentence(s);
        }

        void finish() {
            if (state == 4) state = 5;
            if (state == 5) flush();
            esc = "";
        }
    }
}
