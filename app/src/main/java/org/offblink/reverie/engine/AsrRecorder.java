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
    private static final int RMS_ABS_FLOOR = 700;       // 绝对噪声门（全静音房间不误触）
    private static final float NOISE_MULT = 3.0f;       // 语音判定：噪声底 × 3
    private static final float NOISE_EMA = 0.05f;       // 噪声底更新步长
    private static final int CHUNK_SAMPLES = 1600;      // 100ms 一块
    private static final int PREROLL_CHUNKS = 3;        // 前导 300ms，防吃字头

    public interface Listener {
        void onAudio(byte[] wav);   // 检出语音：WAV 就绪
        void onTimeout();           // 窗口内没听到话
        void onError(String msg);   // 权限/设备故障
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

    private void run(int windowSec, Listener l) {
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) { l.onError("不支持 16k 录音 (minBuf=" + minBuf + ")"); return; }
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord init failed state=" + rec.getState());
                l.onError("录音器初始化失败");
                return;
            }
            rec.startRecording();

            long t0 = System.currentTimeMillis();
            long windowMs = windowSec * 1000L;
            short[] buf = new short[CHUNK_SAMPLES];
            ByteArrayOutputStream cap = new ByteArrayOutputStream();   // 已捕获 PCM
            ArrayDeque<byte[]> preroll = new ArrayDeque<>();
            boolean speaking = false;
            long lastLoudAt = 0;
            double noise = 1200.0;   // 噪声底初值：桌面环境常见底噪

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
                    if (rms > Math.max(RMS_ABS_FLOOR, noise * NOISE_MULT)) {
                        speaking = true;
                        for (byte[] p : preroll) cap.write(p);
                        cap.write(pcm);
                        lastLoudAt = now;
                        Log.i(TAG, "speech start rms=" + rms + " noise=" + (long) noise);
                    } else if (now - t0 >= windowMs) {
                        Log.i(TAG, "window timeout (no speech, " + windowSec + "s)");
                        l.onTimeout();
                        return;
                    }
                } else {
                    cap.write(pcm);
                    if (rms > Math.max(RMS_ABS_FLOOR, noise * NOISE_MULT)) lastLoudAt = now;
                    if (now - lastLoudAt >= SILENCE_STOP_MS || now - t0 >= windowMs) {
                        Log.i(TAG, "capture done bytes=" + cap.size());
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
