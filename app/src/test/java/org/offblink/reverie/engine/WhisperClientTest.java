package org.offblink.reverie.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * whisper 上传契约（SPEC §7）：POST {endpoint}/audio/transcriptions，
 * multipart 带 file/model/language，Authorization 头，解析 {"text"} 并 trim。
 * 端点不存在 → onError("HTTP 404…")，不抛不挂。
 * 手写 ServerSocket 假端点（单测 classpath 无 com.sun.net.httpserver，无 MockWebServer 依赖）。
 */
public class WhisperClientTest {

    private ServerSocket ss;
    private Thread serverThread;
    private volatile String lastPath;
    private volatile String lastBody;
    private volatile String lastAuth;

    @Before
    public void setUp() throws Exception {
        ss = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        serverThread = new Thread(() -> {
            try {
                while (!ss.isClosed()) {
                    try (Socket s = ss.accept()) {
                        handle(s);
                    }
                }
            } catch (IOException ignored) { }   // tearDown 关闭 serverSocket 结束循环
        }, "fake-whisper");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);            // latin-1 一比一映射，够解析头
            if (head.length() > 8 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        String headStr = head.toString();
        int cl = 0;
        for (String line : headStr.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                cl = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        byte[] body = new byte[cl];
        int off = 0;
        while (off < cl) {
            int n = in.read(body, off, cl - off);
            if (n < 0) break;
            off += n;
        }
        lastPath = headStr.split(" ")[1];
        int ai = headStr.toLowerCase().indexOf("authorization:");
        lastAuth = ai >= 0
                ? headStr.substring(ai + 15, headStr.indexOf('\r', ai)).trim() : null;
        lastBody = new String(body, StandardCharsets.ISO_8859_1);

        boolean ok = lastPath.equals("/v1/audio/transcriptions");
        byte[] out = ok ? "{\"text\":\" 第一个 \"}".getBytes(StandardCharsets.UTF_8)
                        : "{}".getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + (ok ? "200 OK" : "404 Not Found")
                + "\r\nContent-Type: application/json\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        os.write(out);
        os.flush();
    }

    @After
    public void tearDown() throws Exception {
        ss.close();
    }

    private String base(String prefix) {
        return "http://127.0.0.1:" + ss.getLocalPort() + prefix;
    }

    private static byte[] fakeWav() {
        return "RIFF....WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void postsMultipartWithAuthAndParsesTrimmedText() throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        new WhisperClient().transcribe(base("/v1"), "sk-test", "whisper-1", fakeWav(),
                new WhisperClient.Listener() {
                    @Override public void onText(String t) { text.set(t); done.countDown(); }
                    @Override public void onError(String m) { err.set(m); done.countDown(); }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(err.get(), err.get());
        assertEquals("第一个", text.get());                       // 识别文本 trim 后回调
        assertEquals("/v1/audio/transcriptions", lastPath);
        assertEquals("Bearer sk-test", lastAuth);
        assertTrue("body 缺 speech.wav: " + lastBody, lastBody.contains("speech.wav"));
        assertTrue("body 缺 model: " + lastBody, lastBody.contains("whisper-1"));
        assertTrue("body 缺 language: " + lastBody, lastBody.contains("zh"));
        assertTrue("body 缺 WAV 载荷: " + lastBody, lastBody.contains("RIFF"));
    }

    @Test
    public void httpErrorGoesToOnError() throws Exception {
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // 端点路径不存在 → 404 → onError，不抛不挂
        new WhisperClient().transcribe(base("/nope"), "", "whisper-1", fakeWav(),
                new WhisperClient.Listener() {
                    @Override public void onText(String t) { done.countDown(); }
                    @Override public void onError(String m) { err.set(m); done.countDown(); }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("err=" + err.get(), err.get() != null && err.get().startsWith("HTTP 404"));
    }
}
