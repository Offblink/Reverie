package org.offblink.reverie.engine;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 端上语音识别（M7，sherpa-onnx v1.13.8）。
 * 模型：streaming zipformer2 CTC 中文 int8（26.3MB，2025-04 导出，宿主机实测 5s 音频 449ms 解码）。
 * 模型在 assets/asr_model/，init 时拷到 filesDir（按体积幂等）；init 必须在后台线程调。
 * ready 前 transcribe 抛 IllegalStateException —— 引擎据此回落云端/报错，不阻塞故事。
 */
public final class DeviceAsr {

    private static final String TAG = "reverie-asr";

    // assets → filesDir 的幂等校验（升级模型时同步改这两个数）
    private static final String MODEL = "model.int8.onnx";
    private static final String TOKENS = "tokens.txt";
    private static final long MODEL_BYTES = 26342340L;
    private static final long TOKENS_BYTES = 13366L;

    private final Context appCtx;
    private volatile OnlineRecognizer recognizer;
    private volatile boolean initFailed;

    public DeviceAsr(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    public boolean ready() { return recognizer != null; }

    /** 后台初始化：拷模型（幂等）+ 建识别器。结果看 ready()/failed()。 */
    public synchronized void init() {
        if (recognizer != null || initFailed) return;
        try {
            File dir = new File(appCtx.getFilesDir(), "asr_model");
            extract(dir);
            long t0 = System.currentTimeMillis();

            OnlineZipformer2CtcModelConfig ctc = new OnlineZipformer2CtcModelConfig();
            ctc.setModel(new File(dir, MODEL).getAbsolutePath());

            OnlineModelConfig m = new OnlineModelConfig();
            m.setZipformer2Ctc(ctc);
            m.setTokens(new File(dir, TOKENS).getAbsolutePath());
            m.setNumThreads(2);
            m.setDebug(false);

            OnlineRecognizerConfig c = new OnlineRecognizerConfig();
            c.setModelConfig(m);
            // 模型走 filesDir 绝对路径 → AssetManager 必须传 null，否则 native 读文件失败
            // （k2-fsa/sherpa-onnx#2562，运行时也会 F 级提示）
            recognizer = new OnlineRecognizer((android.content.res.AssetManager) null, c);
            Log.i(TAG, "device-asr ready in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable e) {   // UnsatisfiedLinkError 等也要兜住
            Log.w(TAG, "device-asr init failed", e);
            initFailed = true;
        }
    }

    public boolean failed() { return initFailed; }

    /** 16k float 样本 → 识别文本（空串 = 没听出字）。未就绪抛 IllegalStateException。 */
    public String transcribe(float[] samples) {
        OnlineRecognizer r = recognizer;
        if (r == null) throw new IllegalStateException("device asr not ready");
        OnlineStream s = r.createStream("");
        try {
            s.acceptWaveform(samples, 16000);
            s.inputFinished();
            while (r.isReady(s)) r.decode(s);
            String text = r.getResult(s).getText();
            return text == null ? "" : text.trim();
        } finally {
            s.release();
        }
    }

    /** WAV（PCM16 LE）→ float 样本；扫 RIFF 块找 data，不写死 44 字节头 */
    static float[] wavToFloat(byte[] wav) {
        int off = 12;
        while (off + 8 <= wav.length) {
            String id = new String(wav, off, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int sz = (wav[off + 4] & 0xFF) | ((wav[off + 5] & 0xFF) << 8)
                    | ((wav[off + 6] & 0xFF) << 16) | ((wav[off + 7] & 0xFF) << 24);
            if ("data".equals(id)) { off += 8; break; }
            off += 8 + sz;
        }
        int n = Math.max(0, (wav.length - off) / 2);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = ((short) ((wav[off + i * 2] & 0xFF) | (wav[off + i * 2 + 1] << 8))) / 32768.0f;
        }
        return out;
    }

    private void extract(File dir) throws Exception {
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("mkdir " + dir);
        copyIfWrong(new File(dir, MODEL), MODEL, MODEL_BYTES);
        copyIfWrong(new File(dir, TOKENS), TOKENS, TOKENS_BYTES);
    }

    private void copyIfWrong(File f, String name, long expected) throws Exception {
        if (f.isFile() && f.length() == expected) return;
        try (InputStream in = appCtx.getAssets().open("asr_model/" + name);
             OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (f.length() != expected) throw new IllegalStateException(name + " size " + f.length() + " != " + expected);
    }
}
