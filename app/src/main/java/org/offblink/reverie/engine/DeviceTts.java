package org.offblink.reverie.engine;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 端上中文 TTS（M11，sherpa-onnx v1.13.8 OfflineTts + VITS zh fanchen-wnj）。
 *
 * M11 换代：Kokoro v1.1-zh 真机 RTF 稳定 2–4（合成耗时 = 音频时长 2–4 倍），
 * 句间必然断粮。VITS fanchen-wnj 参数量约为 Kokoro 的 1/3 且非自回归单次前向，
 * 同机 RTF 预期降 2–4 倍。代价：单说话人（成年男声）+ 自然度不如 Kokoro。
 * 旧 Kokoro 资产仍在 git（M9 提交 `563ec98` 前的 assets/tts_model 树），可一键回退。
 *
 * 模型在 assets/tts_model/（onnx 121MB 因 GitHub 100MiB 限制分两块，init 时拼接），
 * init 拷到 filesDir；init 必须在后台线程调（首启拷 137MB + 加载，秒级到十秒级）。
 *
 * 与 Kokoro 的接线差异（别照抄）：
 *  - VITS 需要 rule FSTs（date/number/phone/new_heteronym）——Kokoro 相反，传了会打断注音查表；
 *  - lexicon.txt 是拼音表（Kokoro 是注音表），采样率 16000；
 *  - 单说话人 → numSpeakers=1，sid 恒 0。
 *
 * 管线（两线程，句间无空隙 + 句尾固定静音垫）：
 *   worker：  队列取句 → generate（CPU 合成）→ 句尾垫静音 → 送 playQueue
 *   player：  取音频 → AudioTrack 播 → onStart(id) 开播 / onDone(id) 播完
 *
 * stop() 语义与系统 TTS 一致：在途句不再回调 onDone（TtsPlayer 手动结算）。
 */
public final class DeviceTts {

    private static final String TAG = "reverie-tts-dev";

    // assets → filesDir 的幂等校验（换模型时同步改）；onnx 在 assets 里分块，这里拼接后的总长
    private static final String ONNX = "model.onnx";
    private static final String ONNX_A = "model.onnx.0";
    private static final String ONNX_B = "model.onnx.1";
    private static final long ONNX_BYTES = 121076185L;
    private static final long LEXICON_BYTES = 2457843L;
    private static final long TOKENS_BYTES = 331L;

    /** 中文文本正规化的 rule FST（VITS 必须有：日期/数字/多音字/电话读法） */
    private static final String[] FST_FILES = {
            "date.fst", "number.fst", "phone.fst", "new_heteronym.fst",
    };
    private static final long[] FST_BYTES = { 59154L, 64482L, 88630L, 21974L };

    /** jieba 分词 dict（zh 词级 lexicon 查表需要） */
    private static final String[] DICT_FILES = {
            "dict/hmm_model.utf8", "dict/idf.utf8", "dict/jieba.dict.utf8",
            "dict/stop_words.utf8", "dict/user.dict.utf8",
            "dict/pos_dict/char_state_tab.utf8", "dict/pos_dict/prob_emit.utf8",
            "dict/pos_dict/prob_start.utf8", "dict/pos_dict/prob_trans.utf8",
    };
    private static final long[] DICT_BYTES = {
            519739L, 5998717L, 5071204L, 8974L, 49L, 327139L, 1687686L, 4347L, 124159L,
    };

    /**
     * 音色表（M11）：fanchen-wnj 是单说话人成年男声，只有 sid 0。
     * 名字保留在设置里（voice 抽屉/试听链路不变），旧 Kokoro 名（zm_010 等）查不到 → 回落 0。
     */
    static final String[] VOICE_NAMES = { "wnj_male" };

    public static final int DEFAULT_SID = 0;

    /** 音色名 → sid；不认识返回 -1 */
    public static int sidForName(String name) {
        if (name == null || name.isEmpty()) return -1;
        for (int i = 0; i < VOICE_NAMES.length; i++) {
            if (VOICE_NAMES[i].equals(name)) return i;
        }
        return -1;
    }

    /** 中文音色清单（设置页抽屉用） */
    public static List<String> zhVoiceNames() {
        List<String> out = new ArrayList<>();
        for (String n : VOICE_NAMES) out.add(n);
        return out;
    }

    /** 试听/兜底文案 */
    static final String PREVIEW_TEXT = "夜深了，月亮悄悄爬上窗台。今晚的故事，现在开始。";

    /**
     * 句尾静音垫（M9）：0.45s 在真机上被用户反馈「句间隔太大」，降到 0.15s。
     * VITS 自带句读停顿时长更规矩，这个垫子只留气口。
     */
    private static final float TAIL_SILENCE_SEC = 0.15f;

    /** 播放回调（在 player 线程调用，接收方自己加锁——与系统 TTS binder 回调同口径） */
    public interface Listener {
        void onStart(String id);   // 该句开始出声（JS 高亮）
        void onDone(String id);    // 该句播完（TtsPlayer 记账推进）
    }

    private static final class Job {
        final String id;
        final String text;
        final int sid;   // <0 = 用当前默认
        Job(String id, String text, int sid) { this.id = id; this.text = text; this.sid = sid; }
    }

    private static final class Clip {
        final String id;
        final short[] pcm;
        final int sampleRate;
        Clip(String id, short[] pcm, int sampleRate) { this.id = id; this.pcm = pcm; this.sampleRate = sampleRate; }
    }

    private final Context appCtx;
    private final Listener listener;
    private volatile OfflineTts tts;
    private volatile boolean initFailed;
    private volatile float rate = 1.0f;
    private volatile int sid = DEFAULT_SID;

    private final LinkedBlockingQueue<Job> jobs = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Clip> clips = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker, player;
    private volatile AudioTrack track;   // stop() 跨线程停音用

    public DeviceTts(Context ctx, Listener listener) {
        appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean ready() { return tts != null; }
    public boolean failed() { return initFailed; }

    /** 语速（0.7–1.1，与系统 TTS 同口径）；下一句生效 */
    public void setRate(float r) { rate = r; }

    /** 音色 sid；下一句生效（VITS 只有 0） */
    public void setVoice(int newSid) {
        if (newSid >= 0 && newSid < VOICE_NAMES.length) sid = newSid;
    }

    public int currentSid() { return sid; }

    /** 后台初始化：拼接/拷模型（幂等）+ 建合成器 + 起管线线程。 */
    public synchronized void init() {
        if (tts != null || initFailed) return;
        try {
            File dir = new File(appCtx.getFilesDir(), "tts_model");
            extract(dir);
            long t0 = System.currentTimeMillis();

            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
            vits.setModel(new File(dir, ONNX).getAbsolutePath());
            vits.setLexicon(new File(dir, "lexicon.txt").getAbsolutePath());
            vits.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
            vits.setDictDir(new File(dir, "dict").getAbsolutePath());
            // 单说话人模型：dataDir 不用（留给 espeak 兜底的模型才需要），噪声/时长缩放走默认

            OfflineTtsModelConfig m = new OfflineTtsModelConfig();
            m.setVits(vits);
            int threads = Math.min(6, Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
            m.setNumThreads(threads);
            Diag.add("tts", "合成线程数=" + threads + " (cores=" + Runtime.getRuntime().availableProcessors() + ")");
            m.setDebug(false);

            OfflineTtsConfig c = new OfflineTtsConfig();
            c.setModel(m);
            // VITS 需要 rule FSTs（与 Kokoro 相反）：日期/数字/多音字/电话读法
            StringBuilder fsts = new StringBuilder();
            for (String f : FST_FILES) {
                if (fsts.length() > 0) fsts.append(',');
                fsts.append(new File(dir, f).getAbsolutePath());
            }
            c.setRuleFsts(fsts.toString());

            // 模型走 filesDir 绝对路径 → AssetManager 传 null（与 DeviceAsr 同口径，k2-fsa/sherpa-onnx#2562）
            tts = new OfflineTts((AssetManager) null, c);

            running.set(true);
            worker = new Thread(this::workerLoop, "reverie-tts-gen");
            player = new Thread(this::playerLoop, "reverie-tts-play");
            worker.start();
            player.start();
            Log.i(TAG, "device-tts(vits) ready in " + (System.currentTimeMillis() - t0)
                    + "ms sampleRate=" + tts.sampleRate() + " speakers=" + tts.numSpeakers());
            Diag.add("tts", "vits 就绪 耗时" + (System.currentTimeMillis() - t0)
                    + "ms sr=" + tts.sampleRate() + " speakers=" + tts.numSpeakers());
        } catch (Throwable e) {   // UnsatisfiedLinkError 等也要兜住
            Log.w(TAG, "device-tts init failed", e);
            Diag.add("tts", "vits 初始化失败: " + e);
            initFailed = true;
        }
    }

    /** 一句入队（用当前音色）；未就绪抛 IllegalStateException（调用方降级/报错） */
    public void speak(String id, String text) {
        speak(id, text, -1);
    }

    /** 一句入队；explicitSid >=0 时强制用该音色（试听不改默认） */
    public void speak(String id, String text, int explicitSid) {
        if (tts == null) throw new IllegalStateException("device tts not ready");
        jobs.offer(new Job(id, text, explicitSid));
    }

    /** 停当前句 + 清两级队列；在途句不再回调 */
    public void stop() {
        clips.clear();
        while (jobs.poll() != null) { }
        AudioTrack t = track;
        if (t != null) {
            try { t.stop(); t.flush(); } catch (IllegalStateException ignored) { }
        }
    }

    public synchronized void shutdown() {
        running.set(false);
        stop();
        AudioTrack t = track;
        track = null;
        if (t != null) { try { t.release(); } catch (Exception ignored) { } }
        OfflineTts cur = tts;
        tts = null;
        if (cur != null) { try { cur.release(); } catch (Exception ignored) { } }
        if (worker != null) worker.interrupt();
        if (player != null) player.interrupt();
    }

    /* ================= 合成线程 ================= */

    private void workerLoop() {
        while (running.get()) {
            Job j;
            try { j = jobs.take(); } catch (InterruptedException e) { return; }
            if (!running.get()) return;
            int useSid = j.sid >= 0 ? j.sid : sid;
            try {
                long t0 = System.currentTimeMillis();
                GeneratedAudio ga = tts.generate(j.text, useSid, rate);
                float[] s = ga != null ? ga.getSamples() : null;
                if (s == null || s.length == 0) {
                    Log.w(TAG, "empty audio for " + j.id);
                    listener.onStart(j.id);   // 空音频也走完整记账，防段卡在 inFlight
                    listener.onDone(j.id);
                    continue;
                }
                clips.put(new Clip(j.id, floatToPcm16(padTail(s, ga.getSampleRate())), ga.getSampleRate()));
                long genMs = System.currentTimeMillis() - t0;
                long audioMs = s.length * 1000L / ga.getSampleRate();
                // RTF = 合成耗时 / 音频时长。>1 表示合成赶不上播放，句间必然出现等待
                Diag.add("tts", "gen " + j.id + " " + audioMs + "ms音频 耗时" + genMs
                        + "ms RTF=" + String.format(java.util.Locale.US, "%.2f", genMs / (float) Math.max(1, audioMs))
                        + " sid=" + useSid + " 队列=" + clips.size());
            } catch (Throwable e) {
                Log.w(TAG, "generate failed " + j.id, e);
                listener.onStart(j.id);
                listener.onDone(j.id);   // 记账照走，TtsPlayer 看门狗兜底其余异常
            }
        }
    }

    /* ================= 播放线程 ================= */

    private void playerLoop() {
        while (running.get()) {
            Clip c;
            long waitStart = System.currentTimeMillis();
            try { c = clips.take(); } catch (InterruptedException e) { return; }
            long waitMs = System.currentTimeMillis() - waitStart;
            if (waitMs > 80) {
                // 播放端在 clips.take() 上阻塞 = 队列见底，这就是用户听到的"句间空档"
                Diag.add("tts", "断粮 " + waitMs + "ms（等下一句合成）");
            }
            if (!running.get()) return;
            listener.onStart(c.id);
            try {
                play(c);
            } catch (Throwable e) {
                Log.w(TAG, "play failed " + c.id, e);
            }
            Diag.add("tts", "播完 " + c.id + " " + (c.pcm.length * 1000L / c.sampleRate) + "ms音频");
            listener.onDone(c.id);
        }
    }

    /** AudioTrack 流式写（阻塞写自带节流）；写完等播放头走到尾 */
    private void play(Clip c) {
        int chMask = AudioFormat.CHANNEL_OUT_MONO;
        int minBuf = AudioTrack.getMinBufferSize(c.sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new IllegalStateException("bad audio params sr=" + c.sampleRate);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(c.sampleRate)
                        .setChannelMask(chMask)
                        .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track = t;
        try {
            t.play();
            int total = c.pcm.length;
            int off = 0;
            while (off < total && running.get()) {
                int n = t.write(c.pcm, off, Math.min(4096, total - off));
                if (n <= 0) break;
                off += n;
            }
            // 等播完（写完 ≠ 出完声）：播放头到尾或 60s 上限
            long deadline = System.currentTimeMillis() + 60_000;
            while (running.get() && t.getPlaybackHeadPosition() < total
                    && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(120); } catch (InterruptedException e) { return; }
            }
        } finally {
            try { t.stop(); } catch (IllegalStateException ignored) { }
            t.release();
            if (track == t) track = null;
        }
    }

    /** float[-1,1] → PCM16 LE（wavToFloat 的逆变换）；越界钳位 */
    static short[] floatToPcm16(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float v = samples[i];
            if (v > 1f) v = 1f;
            else if (v < -1f) v = -1f;
            out[i] = (short) Math.round(v * 32767f);
        }
        return out;
    }

    /** 句尾静音垫：让每句自带停顿，句间衔接不再硬切（M9 停顿修复） */
    static float[] padTail(float[] samples, int sampleRate) {
        int pad = Math.round(sampleRate * TAIL_SILENCE_SEC);
        float[] out = new float[samples.length + pad];
        System.arraycopy(samples, 0, out, 0, samples.length);
        return out;
    }

    /* ================= assets 拷贝（幂等） ================= */

    private void extract(File dir) throws Exception {
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("mkdir " + dir);
        File onnx = new File(dir, ONNX);
        if (!(onnx.isFile() && onnx.length() == ONNX_BYTES)) {
            // 首启拼接：分块 → onnx（原子性：先写临时文件再 rename，中途被杀不会留半个模型）
            File tmp = new File(dir, ONNX + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                copyAsset(ONNX_A, out);
                copyAsset(ONNX_B, out);
            }
            if (tmp.length() != ONNX_BYTES) throw new IllegalStateException("onnx " + tmp.length());
            if (!tmp.renameTo(onnx)) throw new IllegalStateException("rename onnx");
        }
        copyIfWrong(new File(dir, "lexicon.txt"), "lexicon.txt", LEXICON_BYTES);
        copyIfWrong(new File(dir, "tokens.txt"), "tokens.txt", TOKENS_BYTES);
        for (int i = 0; i < FST_FILES.length; i++) {
            copyIfWrong(new File(dir, FST_FILES[i]), FST_FILES[i], FST_BYTES[i]);
        }
        for (int i = 0; i < DICT_FILES.length; i++) {
            copyIfWrong(new File(dir, DICT_FILES[i]), DICT_FILES[i], DICT_BYTES[i]);
        }
    }

    private void copyAsset(String name, OutputStream out) throws Exception {
        try (InputStream in = appCtx.getAssets().open("tts_model/" + name)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void copyIfWrong(File f, String name, long expected) throws Exception {
        if (f.isFile() && f.length() == expected) return;
        File parent = f.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("mkdir " + parent);
        }
        try (InputStream in = appCtx.getAssets().open("tts_model/" + name);
             OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (f.length() != expected) throw new IllegalStateException(name + " size " + f.length() + " != " + expected);
    }
}
