package org.offblink.reverie.engine;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * 16 kHz 单声道 16-bit PCM 录音 + 能量 VAD（SPEC §7）。
 *
 * 窗口语义（一个窗口 = 一次开麦）：
 *   WAITING（等说话，留 300ms 前导）→（能量过阈）CAPTURING
 *   CAPTURING →（连续静音 1.2s 或窗口到顶）onAudio(WAV)
 *   WAITING 到窗口仍无语音 → onTimeout
 *   外部 stop()（手动关麦/换故事/退后台）→ 丢弃，无回调
 *
 * 阈值自适应：等说话阶段对噪声底做 EMA，语音 = max(绝对门限, 噪声底×3)。
 * 线程：独立录音线程（AudioRecord.read 阻塞 ~100ms/块），Listener 回调在该线程，
 * 引擎侧自行 hop 到自己的 executor。
 */
public final class AsrRecorder {

    private static final String TAG = "reverie-asr";
    private static final int SAMPLE_RATE = 16000;
    private static final long SILENCE_STOP_MS = 1200;   // 连续静音 1.2s 截断
    private static final int RMS_ABS_FLOOR = 500;       // 绝对噪声门（全静音房间不误触；M9 700→500：睡前轻声）
    private static final float NOISE_MULT = 2.6f;       // 语音判定：噪声底 × 2.6（M9 3.0→2.6：轻声检出）
    private static final float NOISE_EMA = 0.15f;       // 噪声底更新步长（M9 0.05→0.15：初值 1200 的高阈值
                                                        //  要 2s 才衰减到真实底噪，开麦头 2s 轻声必漏——0.15 约 0.4s 收敛。
                                                        //  仍安全：EMA 只在 rms<噪声×3 的静音块更新，语音不会拉高噪声底）
    private static final int CHUNK_SAMPLES = 1600;      // 100ms 一块
    private static final int PREROLL_CHUNKS = 3;        // 前导 300ms，防吃字头

    public interface Listener {
        void onAudio(byte[] wav);        // 检出语音：WAV 就绪
        void onTimeout(int peakRms);     // 窗口内没听到话；带峰值音量供诊断（M9）
        void onError(String msg);        // 权限/设备故障
        /** 实时电平（M10，约 200ms 一次）：rms 与当前语音门限，设置页诊断面板画电平条 */
        default void onLevel(int rms, int threshold) {}
    }

    private final Context appCtx;
    private Thread th;              // 当前录音线程（synchronized 访问）
    private volatile boolean running;

    public AsrRecorder(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    /** 开一个监听窗口；同一时间只允许一个（已在录返回 false，不回调） */
    public synchronized boolean start(int windowSec, Listener l) {
        if (th != null) return false;
        if (appCtx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            l.onError("没有麦克风权限");
            return false;
        }
        running = true;
        th = new Thread(() -> run(Math.max(3, windowSec), l), "reverie-asr");
        th.start();
        return true;
    }

    /** 手动关麦：丢弃当前录音（不回调——JS 自己清倒计时环） */
    public synchronized void stop() {
        running = false;
    }

    /**
     * 麦克风自检（M10）：只测电平不判语音，6 秒后 onTimeout(峰值) 收尾。
     * 设置页「麦克风自检」按钮走这里——不依赖故事流程，直接回答"麦克风到底有没有信号"。
     */
    public synchronized boolean startTest(int sec, Listener l) {
        if (th != null) return false;
        if (appCtx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            l.onError("没有麦克风权限");
            return false;
        }
        running = true;
        final int s = Math.max(2, sec);
        th = new Thread(() -> runLevelTest(s, l), "reverie-asr-test");
        th.start();
        return true;
    }

    private void runLevelTest(int sec, Listener l) {
        AudioRecord rec = null;
        long peak1 = 0;   // VOICE_RECOGNITION 音源峰值
        long peak2 = 0;   // MIC 音源峰值
        try {
            short[] buf = new short[CHUNK_SAMPLES];
            long t0 = System.currentTimeMillis();
            int phase = 1;
            rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            double noise = 1200.0;
            int chunk = 0;
            while (running && System.currentTimeMillis() - t0 < sec * 1000L) {
                long now = System.currentTimeMillis();
                // 后半段若仍零信号 → 换 MIC 音源再测（同一台设备两个音源行为可能不同）
                if (phase == 1 && peak1 < 10 && now - t0 >= sec * 500L) {
                    phase = 2;
                    Diag.add("asr", "自检前半段零信号，切 MIC 音源继续测");
                    try { rec.stop(); } catch (Exception ignored) { }
                    try { rec.release(); } catch (Exception ignored) { }
                    rec = open(MediaRecorder.AudioSource.MIC);
                    noise = 1200.0;
                    continue;
                }
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) { l.onError("读麦克风失败 n=" + n); return; }
                long rms = rms(buf, n);
                if (phase == 1) { if (rms > peak1) peak1 = rms; } else { if (rms > peak2) peak2 = rms; }
                if (rms < noise * NOISE_MULT) noise = noise * (1 - NOISE_EMA) + rms * NOISE_EMA;
                if ((chunk++ & 1) == 0) {
                    l.onLevel((int) rms, (int) Math.max(RMS_ABS_FLOOR, noise * NOISE_MULT));
                }
            }
            Diag.add("asr", "自检 " + sec + "s peak(VR)=" + peak1 + " peak(MIC)=" + peak2);
            if (peak1 >= 10) l.onTimeout((int) peak1);
            else if (peak2 >= 10) l.onTimeout((int) peak2);   // 有信号：靠 MIC 音源，故事流程已自动兜底
            else l.onError("自检 " + sec + "s 两个音源都没有任何信号（权限被系统限制 / 被其他应用占用 / 硬件问题）");
        } catch (Exception e) {
            Log.w(TAG, "level test failed", e);
            l.onError("自检失败: " + e.getMessage());
        } finally {
            try {
                if (rec != null) { if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) rec.stop(); rec.release(); }
            } catch (Exception ignored) { }
            synchronized (this) { if (th == Thread.currentThread()) th = null; }
        }
    }

    /** 打开录音器（统一记诊断日志） */
    private AudioRecord open(int source) {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new IllegalStateException("不支持 16k 录音 (minBuf=" + minBuf + ")");
        AudioRecord r = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IllegalStateException("录音器初始化失败");
        }
        r.startRecording();
        Diag.add("asr", "录音开 音源=" + (source == MediaRecorder.AudioSource.MIC ? "MIC" : "VOICE_RECOGNITION")
                + " minBuf=" + minBuf);
        return r;
    }

    private void run(int windowSec, Listener l) {
        AudioRecord rec = null;
        try {
            rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION);

            long t0 = System.currentTimeMillis();
            long windowMs = windowSec * 1000L;
            short[] buf = new short[CHUNK_SAMPLES];
            ByteArrayOutputStream cap = new ByteArrayOutputStream();   // 已捕获 PCM
            ArrayDeque<byte[]> preroll = new ArrayDeque<>();
            boolean speaking = false;
            long lastLoudAt = 0;
            long peakRms = 0;
            int chunkNo = 0;
            boolean triedMic = false;        // 窗口内最大音量（M9 诊断：超时 toast 据此区分"没出声"vs"没过阈"）
            double noise = 1200.0;   // 噪声底初值：桌面环境常见底噪（EMA 0.15 约 0.4s 收敛到真实底噪）

            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) { l.onError("读麦克风失败 n=" + n); return; }
                long now = System.currentTimeMillis();
                long rms = rms(buf, n);
                byte[] pcm = toBytes(buf, n);

                if (!speaking) {
                    preroll.addLast(pcm);
                    if (preroll.size() > PREROLL_CHUNKS) preroll.removeFirst();
                    if (rms < noise * NOISE_MULT) noise = noise * (1 - NOISE_EMA) + rms * NOISE_EMA;
                    if (rms > peakRms) peakRms = rms;
                    final int thr = (int) Math.max(RMS_ABS_FLOOR, noise * NOISE_MULT);
                    if ((chunkNo++ & 1) == 0) l.onLevel((int) rms, thr);
                    // 零信号兜底（M10）：1.5s 内全是 0 → 个别 ROM（含 MIUI）对
                    // VOICE_RECOGNITION 音源返回静音，换 MIC 重开
                    if (peakRms < 10 && !triedMic && now - t0 >= 1500) {
                        triedMic = true;
                        Diag.add("asr", "1.5s 零信号，改试 MIC 音源");
                        try { rec.stop(); } catch (Exception ignored) { }
                        try { rec.release(); } catch (Exception ignored) { }
                        rec = open(MediaRecorder.AudioSource.MIC);
                        preroll.clear();
                        continue;
                    }
                    if (rms > Math.max(RMS_ABS_FLOOR, noise * NOISE_MULT)) {
                        speaking = true;
                        for (byte[] p : preroll) cap.write(p);
                        cap.write(pcm);
                        lastLoudAt = now;
                        Diag.add("asr", "检出语音 rms=" + rms + " noise=" + (long) noise
                                + " thr=" + (long) Math.max(RMS_ABS_FLOOR, noise * NOISE_MULT)
                                + " 音源=" + (triedMic ? "MIC" : "VOICE_RECOGNITION"));
                    } else if (now - t0 >= windowMs) {
                        Diag.add("asr", "窗口超时 " + windowSec + "s peak=" + peakRms
                                + " noise=" + (long) noise + " 音源=" + (triedMic ? "MIC" : "VOICE_RECOGNITION"));
                        if (peakRms < 10) {
                            l.onError("麦克风没有任何信号（检查系统权限 / 是否被其他应用占用）");
                        } else {
                            l.onTimeout((int) peakRms);
                        }
                        return;
                    }
                } else {
                    cap.write(pcm);
                    if (rms > Math.max(RMS_ABS_FLOOR, noise * NOISE_MULT)) lastLoudAt = now;
                    if (now - lastLoudAt >= SILENCE_STOP_MS || now - t0 >= windowMs) {
                        Diag.add("asr", "录音截断 bytes=" + cap.size());
                        l.onAudio(wrapWav(cap.toByteArray()));
                        return;
                    }
                }
            }
            // running=false：手动 stop，丢弃无回调
        } catch (Exception e) {
            Log.w(TAG, "record failed", e);
            l.onError("录音失败: " + e.getMessage());
        } finally {
            try {
                if (rec != null) { if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) rec.stop(); rec.release(); }
            } catch (Exception ignored) { }
            synchronized (this) { if (th == Thread.currentThread()) th = null; }
        }
    }

    private static long rms(short[] buf, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += (long) buf[i] * buf[i];
        return (long) Math.sqrt((double) sum / n);
    }

    private static byte[] toBytes(short[] buf, int n) {
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            out[i * 2] = (byte) (buf[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((buf[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** PCM → WAV（16k/mono/16-bit，44 字节标准头，不引 lame） */
    static byte[] wrapWav(byte[] pcm) {
        byte[] wav = new byte[44 + pcm.length];
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + pcm.length);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);                                   // PCM
        b.putShort((short) 1);                                   // mono
        b.putInt(SAMPLE_RATE);
        b.putInt(SAMPLE_RATE * 2);                               // byte rate
        b.putShort((short) 2);                                   // block align
        b.putShort((short) 16);                                  // bits
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(pcm.length);
        b.put(pcm);
        return wav;
    }
}
