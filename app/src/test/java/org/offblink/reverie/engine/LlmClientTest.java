package org.offblink.reverie.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * BASE URL 归一化（M8，修「配置没问题却 404 且报错空洞」）。
 * 用户输入的三类真实脏值：粘完整接口路径、带换行/全角空格、少写协议。
 */
public class LlmClientTest {

    @Test
    public void stripsDuplicatePathSuffix() {
        assertEquals("https://api.xiaomimimo.com/v1",
                LlmClient.normalizeBase("https://api.xiaomimimo.com/v1/chat/completions"));
        // 连续粘两层也剥干净
        assertEquals("https://api.xiaomimimo.com/v1",
                LlmClient.normalizeBase("https://api.xiaomimimo.com/v1/chat/completions/chat/completions"));
    }

    @Test
    public void stripsTrailingSlashAndWhitespace() {
        assertEquals("https://api.xiaomimimo.com/v1",
                LlmClient.normalizeBase("  https://api.xiaomimimo.com/v1/  "));
        // 手机剪贴板带换行（M6 实测污染 Authorization 头的同源脏值）
        assertEquals("https://api.xiaomimimo.com/v1",
                LlmClient.normalizeBase("https://api.xiaomimimo.com/v1\r\n"));
        // 全角空格
        assertEquals("https://api.xiaomimimo.com/v1",
                LlmClient.normalizeBase("\u3000https://api.xiaomimimo.com/v1\u3000"));
    }

    @Test
    public void addsMissingScheme() {
        assertEquals("https://api.xiaomimimo.com/v1",
                LlmClient.normalizeBase("api.xiaomimimo.com/v1"));
    }

    @Test
    public void keepsPlainBaseUntouched() {
        assertEquals("https://api.openai.com/v1",
                LlmClient.normalizeBase("https://api.openai.com/v1"));
        // /v1 结尾不是后缀剥离目标（tails 只匹配接口名）
        assertEquals("https://api.example.com/completions/v1",
                LlmClient.normalizeBase("https://api.example.com/completions/v1"));
    }

    @Test
    public void emptyInputsStayEmpty() {
        assertEquals("", LlmClient.normalizeBase(null));
        assertEquals("", LlmClient.normalizeBase("   "));
        assertEquals("", LlmClient.normalizeBase("\r\n"));
    }

    @Test
    public void httpErrorShowsUrlEvenWhenBodyEmpty() {
        // 用户报的原始症状：「HTTP 404: 」冒号后面空 —— 现在必须带 URL 与自检指引
        String m = LlmClient.httpError(404, "https://api.xiaomimimo.com/v1/chat/completions", "");
        assertTrue(m.contains("404"));
        assertTrue(m.contains("https://api.xiaomimimo.com/v1/chat/completions"));
        assertTrue(m.contains("响应体为空"));
    }

    @Test
    public void httpErrorKeepsBodyWhenPresent() {
        String m = LlmClient.httpError(401, "https://x/v1/chat/completions", "{\"error\":\"bad key\"}");
        assertTrue(m.contains("401"));
        assertTrue(m.contains("bad key"));
    }
}
