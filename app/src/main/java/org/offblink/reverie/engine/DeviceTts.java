package org.offblink.reverie.engine;

import android.content.Context;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 端上中文 TTS（M8，sherpa-onnx v1.13.8 OfflineTts + VITS fanchen-C，187 音色）。
 *
 * 动机：部分手机（如无中文 TTS 引擎的小米）系统 TTS 无中文 → M4 起只能降级纯文字。
 * 模型在 assets/tts_model/（onnx 因 GitHub 100MiB 限制分两块，init 时拼接），init 拷到 filesDir；
 * init 必须在后台线程调（首启拷 137MB + 加载模型，秒级）。
 *
 * 管线（两线程，句间无空隙）：
 *   worker：  队列取句 → generate（CPU 合成）→ 送 playQueue
 *   player：  取音频 → AudioTrack 播 → onStart(id) 开播 / onDone(id) 播完
 * 预取深度 1：合成 ~2-4s « 播放 ~15-20s，恒定领先一句。
 *
 * stop() 语义与系统 TTS 一致：在途句不再回调 onDone（TtsPlayer 手动结算）。
 */
public final class DeviceTts {

    private static final String TAG = "reverie-tts-dev";

    // assets → filesDir 的幂等校验（换模型时同步改）；onnx 在 assets 里分块，这里拼接后的总长
    private static final String ONNX = "model.onnx";
    private static final String ONNX_A = "model.onnx.0";
    private static final String ONNX_B = "model.onnx.1";
    private static final long ONNX_BYTES = 121266652L;
    private static final String[] SMALL_FILES = {
            "tokens.txt", "lexicon.txt", "date.fst", "new_heteronym.fst", "number.fst", "phone.fst",
    };
    private static final long[] SMALL_BYTES = {
            331L, 2457843L, 59154L, 21974L, 64482L, 88630L,
    };
    private static final String[] DICT_FILES = {
            "dict/hmm_model.utf8", "dict/idf.utf8", "dict/jieba.dict.utf8",
            "dict/stop_words.utf8", "dict/user.dict.utf8",
            "dict/pos_dict/char_state_tab.utf8", "dict/pos_dict/prob_emit.utf8",
            "dict/pos_dict/prob_start.utf8", "dict/pos_dict/prob_trans.utf8",
    };
    private static final long[] DICT_BYTES = {
            519739L, 5998717L, 5071204L, 8974L, 49L, 327139L, 1687686L, 4347L, 124159L,
    };

    /** 播放回调（在 player 线程调用，接收方自己加锁——与系统 TTS binder 回调同口径） */
    public interface Listener {
        void onStart(String id);   // 该句开始出声（JS 高亮）
        void onDone(String id);    // 该句播完（TtsPlayer 记账推进）
    }

    private static final class Job {
        final String id;
        final String text;
        Job(String id, String text) { this.id = id; this.text = text; }
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

            OfflineTtsModelConfig m = new OfflineTtsModelConfig();
            m.setVits(vits);
            m.setNumThreads(2);
            m.setDebug(false);

            OfflineTtsConfig c = new OfflineTtsConfig();
            c.setModel(m);
            c.setRuleFsts(joinFsts(dir));

            // 模型走 filesDir 绝对路径 → AssetManager 传 null（与 DeviceAsr 同口径，k2-fsa/sherpa-onnx#2562）
            tts = new OfflineTts((android.content.res.AssetManager) null, c);

            running.set(true);
            worker = new Thread(this::workerLoop, "reverie-tts-gen");
            player = new Thread(this::playerLoop, "reverie-tts-play");
            worker.start();
            player.start();
            Log.i(TAG, "device-tts ready in " + (System.currentTimeMillis() - t0)
                    + "ms sampleRate=" + tts.sampleRate() + " speakers=" + tts.numSpeakers());
        } catch (Throwable e) {   // UnsatisfiedLinkError 等也要兜住
            Log.w(TAG, "device-tts init failed", e);
            initFailed = true;
        }
    }

    /** 一句入队；未就绪抛 IllegalStateException（调用方降级/报错） */
    public void speak(String id, String text) {
        if (tts == null) throw new IllegalStateException("device tts not ready");
        jobs.offer(new Job(id, text));
    }

    /** 停当前句 + 清两级队列；在途句不再回调 */
    public void stop() {
        clips.clear();
        // jobs 逐个清（clear 与 offer 竞态时最多漏一句，随后 worker 自查 running）
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
            try {
                long t0 = System.currentTimeMillis();
                GeneratedAudio ga = tts.generate(j.text, 0, rate);
                float[] s = ga != null ? ga.getSamples() : null;
                if (s == null || s.length == 0) {
                    Log.w(TAG, "empty audio for " + j.id);
                    listener.onStart(j.id);   // 空音频也走完整记账，防段卡在 inFlight
                    listener.onDone(j.id);
                    continue;
                }
                clips.put(new Clip(j.id, floatToPcm16(s), ga.getSampleRate()));
                Log.d(TAG, "gen " + j.id + " " + s.length + " samples in "
                        + (System.currentTimeMillis() - t0) + "ms");
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
            try { c = clips.take(); } catch (InterruptedException e) { return; }
            if (!running.get()) return;
            listener.onStart(c.id);
            try {
                play(c);
            } catch (Throwable e) {
                Log.w(TAG, "play failed " + c.id, e);
            }
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

    /** rule FSTs：日期/数字/异读字归一化（逗号拼接绝对路径，缺文件不致命——sherpa 跳过不存在项） */
    private static String joinFsts(File dir) {
        StringBuilder sb = new StringBuilder();
        for (String n : new String[]{"date.fst", "number.fst", "new_heteronym.fst", "phone.fst"}) {
            if (sb.length() > 0) sb.append(',');
            sb.append(new File(dir, n).getAbsolutePath());
        }
        return sb.toString();
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
        for (int i = 0; i < SMALL_FILES.length; i++) {
            copyIfWrong(new File(dir, SMALL_FILES[i]), SMALL_FILES[i], SMALL_BYTES[i]);
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
