package org.offblink.reverie.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 系统 TTS 播讲器（SPEC §6）。
 *
 * - 逐句入队（QUEUE_ADD），utteranceId = "g/r#seg#seq"，回调据此记账
 * - 按段汇报读完：段 sealed 且句全部 onDone → Listener.onSegmentRead(seg)
 *   —— JS 弹选项坞的唯一触发点（读完才出选项，SPEC §6）
 * - 降级不死锁：init 失败/超时 / 无中文 / speak 拒收 → 全部句按读完结算，
 *   onSegmentRead 照发 → 故事自动转纯文字继续，流程不中断
 * - 看门狗双档：15s 未开读 = 引擎丢了这句 → 跳过该句继续（不杀播放器）；
 *   开读后 45s 未读完 = 引擎卡死 → 整体降级
 * - replay 独立计数：读完 → onReplayDone；重听期间压制段读完，结束后补发
 *
 * 线程：enqueue/seal/replay 来自引擎线程与 OkHttp 线程，onInit/onDone 来自
 * 主线程/binder——全部状态在 synchronized(this) 内；Listener 回调在锁内发出，
 * 引擎侧实现只做无锁事件投递，无回调环。
 */
public final class TtsPlayer extends UtteranceProgressListener
        implements TextToSpeech.OnInitListener {

    private static final String TAG = "reverie-tts";
    private static final long NO_START_MS = 15_000;    // 入队后没开读：丢句跳过
    private static final long STUCK_MS = 45_000;       // 开读后 45s 没读完：引擎卡死
    private static final long INIT_TIMEOUT_MS = 12_000;// init 无回调：降级
    private static final long DEVICE_INIT_TIMEOUT_MS = 90_000; // 端上模型首启拷 137MB+加载

    public interface Listener {
        void onSpeaking(int seg, String text);   // 某句开读（JS 高亮）
        void onSegmentRead(int seg);             // 段读完（JS 弹选项）
        void onReplayDone(int lastSeg);          // 重听队列读完
        void onDegraded(String why);             // 转纯文字模式（JS toast）
    }

    private static final int ST_INIT = 0, ST_OK = 1, ST_DEAD = 2;

    private static final class SegRec {
        int enqueued, done;
        boolean sealed, reported;
    }

    private static final class Item {
        final int seg;
        final String text;
        final boolean replay;
        Item(int seg, String text, boolean replay) { this.seg = seg; this.text = text; this.replay = replay; }
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile float rate;
    private String voiceName = "";   // 音色（M6）；空 = 系统默认
    private final String ttsMode;    // auto=系统优先无中文切端上（默认）| system | device（M8）
    private final DeviceTts deviceTts;

    private TextToSpeech tts;
    private int st = ST_INIT;
    private boolean useDevice = false;       // 当前后端：true=端上 VITS
    private boolean deviceSwitching = false; // 端上引擎初始化中（防重复起线程/防系统超时误杀）

    private final Map<Integer, SegRec> segs = new HashMap<>();
    private final List<Item> undelivered = new ArrayList<>();   // 等 init 的缓冲
    private final List<String> inFlight = new ArrayList<>();    // 已交给 TTS 未结算（保序：队头=最老）
    private final Set<String> started = new HashSet<>();
    private final Set<String> finished = new HashSet<>();
    private final Map<String, Integer> idSeg = new HashMap<>();
    private final Map<String, String> idText = new HashMap<>();
    private long seq = 0;
    private long lastProgressAt = 0;   // 队列最后一次 onStart/onDone 的时刻

    private boolean replayActive = false;
    private int replayOutstanding = 0;
    private int replayLastSeg = -1;

    private final Runnable queueWatch = new Runnable() {
        @Override public void run() {
            synchronized (TtsPlayer.this) {
                // 端上后端首句合成含 JIT 预热，可能 >15s——放宽到 60s，防误丢句
                long noStart = useDevice ? 60_000 : NO_START_MS;
                if (st == ST_OK && !inFlight.isEmpty()
                        && System.currentTimeMillis() - lastProgressAt > noStart) {
                    // 队列长时间无任何动静：引擎丢了队头，跳过它继续（下轮再看新队头）
                    String head = inFlight.get(0);
                    Log.w(TAG, "drop queue head (no progress): " + head);
                    finishLocked(head);
                    lastProgressAt = System.currentTimeMillis();
                }
                if (st == ST_OK && !inFlight.isEmpty()) main.postDelayed(this, 3_000);
            }
        }
    };

    public TtsPlayer(Context ctx, float rate, String ttsMode, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.rate = rate;
        this.ttsMode = ttsMode;
        this.listener = listener;
        // 端上后端：回调直接进系统 TTS 同一套 onStart/onDone 记账（id 语义一致）
        this.deviceTts = new DeviceTts(appCtx, new DeviceTts.Listener() {
            @Override public void onStart(String id) { TtsPlayer.this.onStart(id); }
            @Override public void onDone(String id) { TtsPlayer.this.onDone(id); }
        });
    }

    public void init() {
        if ("device".equals(ttsMode)) {
            switchToDeviceLocked("ttsMode=device");
            return;
        }
        tts = new TextToSpeech(appCtx, this);
        main.postDelayed(() -> {
            synchronized (TtsPlayer.this) {
                if (st == ST_INIT) failOrDeviceLocked("init timeout " + INIT_TIMEOUT_MS + "ms");
            }
        }, INIT_TIMEOUT_MS);
    }

    /** 系统侧失败出口：auto/device 模式先试端上引擎，否则降级纯文字 */
    private void failOrDeviceLocked(String why) {
        if (canTryDeviceLocked()) switchToDeviceLocked(why);
        else degradeLocked(why);
    }

    /** auto/device 模式下端上引擎可用（没在切、没失败过） */
    private boolean canTryDeviceLocked() {
        return !"system".equals(ttsMode) && !deviceSwitching && !useDevice && !deviceTts.failed();
    }

    /**
     * 切到端上引擎：系统侧直接放弃（tts 置空防误用），模型后台初始化；
     * 就绪后 st=ST_OK 起泵。首启拷 137MB+加载模型，90s 硬闸超时即降级。
     * 在途句缓冲在 undelivered，就绪后照常 pump——切引擎不丢句。
     */
    private synchronized void switchToDeviceLocked(String why) {
        if (deviceSwitching || useDevice || st == ST_DEAD) return;
        deviceSwitching = true;
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        Log.i(TAG, "switching to device tts (" + why + ")");
        new Thread(() -> deviceTts.init(), "reverie-tts-dev-init").start();
        final long deadline = System.currentTimeMillis() + DEVICE_INIT_TIMEOUT_MS;
        main.postDelayed(() -> pollDeviceReadyLocked(deadline), 1_000);
    }

    /** 端上初始化轮询：就绪→接管；失败/过硬闸→降级；否则 3s 后再看 */
    private void pollDeviceReadyLocked(long deadline) {
        synchronized (this) {
            if (useDevice || st == ST_DEAD) return;
            if (deviceTts.ready()) {
                deviceSwitching = false;
                useDevice = true;
                st = ST_OK;
                Log.i(TAG, "device tts ready, taking over");
                pumpLocked();
            } else if (deviceTts.failed() || System.currentTimeMillis() >= deadline) {
                deviceSwitching = false;
                degradeLocked(deviceTts.failed() ? "device tts init failed" : "device tts init timeout");
            } else {
                main.postDelayed(() -> pollDeviceReadyLocked(deadline), 3_000);
            }
        }
    }

    public void shutdown() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        deviceTts.shutdown();
    }

    /** 停当前播放（两后端都停；未激活的后端调 stop 无副作用） */
    private void stopPlaybackLocked() {
        if (tts != null) tts.stop();
        if (useDevice || deviceSwitching) deviceTts.stop();
    }

    public void setRate(float r) {
        rate = r;
        deviceTts.setRate(r);   // 端上后端：下一句生效
        synchronized (this) { if (st == ST_OK && tts != null) tts.setSpeechRate(r); }
    }

    /** 音色切换（M6/M9）：kokoro 音色名（zm_ 或 zf_ 前缀）走端上后端；其余给系统 TTS。找不到该名保持当前。 */
    public synchronized void setVoice(String name) {
        voiceName = name == null ? "" : name;
        int sid = DeviceTts.sidForName(voiceName);
        if (sid >= 0) deviceTts.setVoice(sid);   // 未就绪也先存，接管时生效
        applyVoiceLocked();
    }

    /** 音色清单（设置页用）：端上后端 → kokoro 中文音色；系统后端 → 系统音色（中文优先） */
    public synchronized List<String> voices() {
        if (useDevice) return DeviceTts.zhVoiceNames();
        List<String> out = new ArrayList<>();
        if (st != ST_OK || tts == null) return out;
        try {
            List<Voice> vs = new ArrayList<>(tts.getVoices());
            java.util.Collections.sort(vs, (a, b) -> Boolean.compare(!isZh(a), !isZh(b)));
            for (Voice v : vs) out.add(v.getName());
        } catch (Exception e) {
            Log.w(TAG, "getVoices failed: " + e);
        }
        return out;
    }

    /**
     * 试听（M9）：念一句固定文案再停下。当前朗读被打断（在途结算为读完）；
     * 生成中由引擎侧守卫拒绝。name 为 kokoro 音色名时临时用该音色，不改默认。
     */
    public synchronized boolean previewSpeak(String name) {
        if (st != ST_OK) return false;
        stopReplay();
        stopPlaybackLocked();
        settleInFlightLocked();
        String id = "preview#" + (seq++);
        if (useDevice) {
            int sid = DeviceTts.sidForName(name);
            if (sid < 0) sid = deviceTts.currentSid();
            try { deviceTts.speak(id, DeviceTts.PREVIEW_TEXT, sid); }
            catch (Exception e) { return false; }
        } else {
            int rc;
            try { rc = tts.speak(DeviceTts.PREVIEW_TEXT, TextToSpeech.QUEUE_ADD, null, id); }
            catch (Exception e) { rc = TextToSpeech.ERROR; }
            if (rc == TextToSpeech.ERROR) return false;
        }
        return true;
    }

    private void applyVoiceLocked() {
        if (st != ST_OK || tts == null || voiceName.isEmpty()) return;
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs == null) return;
            for (Voice v : vs) {
                if (voiceName.equals(v.getName())) { tts.setVoice(v); return; }
            }
            Log.w(TAG, "voice not found: " + voiceName);
        } catch (Exception e) {
            Log.w(TAG, "setVoice failed: " + e);
        }
    }

    private static boolean isZh(Voice v) {
        Locale l = v.getLocale();
        return l != null && "zh".equals(l.getLanguage());
    }

    /* ================= 生成段管道（g*） ================= */

    /** 一句话入队；init 未完成时先缓冲 */
    public void enqueue(int seg, String text) {
        synchronized (this) {
            rec(seg).enqueued++;
            undelivered.add(new Item(seg, text, false));
            if (st == ST_OK) pumpLocked();
        }
    }

    /** 段落文本到此结束——之后不再 enqueue(seg)；结算该段 */
    public void seal(int seg) {
        synchronized (this) {
            SegRec r = rec(seg);
            r.sealed = true;
            if (st == ST_DEAD) drainDeadLocked();
            reportLocked(seg);
        }
    }

    /* ================= 重听（r*） ================= */

    public synchronized void beginReplay(int lastSeg) {
        // 旧 replay 被取代 → 先作废（不让 settle 把它报成「完成」），再停流、
        // 结算在途、丢缓冲，最后建立新 replay 状态。结算必须在置位新状态之前，
        // 否则新 replay 会在入队前就被 checkReplayDone 误报完成。
        replayActive = false;
        stopPlaybackLocked();
        settleInFlightLocked();
        removeReplayItemsLocked();
        replayActive = true;
        replayLastSeg = lastSeg;
        replayOutstanding = 0;
    }

    public synchronized void replay(int seg, String text) {
        if (!replayActive) return;
        replayOutstanding++;
        undelivered.add(new Item(seg, text, true));
    }

    public synchronized void endReplay() {
        if (st == ST_OK) pumpLocked();
        else if (st == ST_DEAD) { drainDeadLocked(); checkReplayDoneLocked(); }
        // ST_INIT：onInit 后 pump
    }

    public synchronized void stopReplay() {
        if (!replayActive && replayOutstanding == 0) return;
        replayActive = false;
        stopPlaybackLocked();
        settleInFlightLocked();
        replayOutstanding = 0;
        removeReplayItemsLocked();
        reportSuppressedLocked();   // 被重听压住的段读完补发
    }

    /**
     * 生成失败中止：掐掉队列里该段的残句（半截文不该被念出来）。
     * tts.stop() 只影响队列，已入队的 g* 全部按读完结算，重试可重新入队。
     */
    public synchronized void abandon(int seg) {
        stopPlaybackLocked();
        settleInFlightLocked();     // stop 后被取消句的 onDone 不会来，手动结算
        Iterator<Item> it = undelivered.iterator();
        while (it.hasNext()) {
            Item i = it.next();
            if (i.replay) continue;
            it.remove();
            if (i.seg == seg) { rec(i.seg).done++; reportLocked(i.seg); }
        }
        reportSuppressedLocked();
    }

    /** 换故事/打开另一本：停音、清队列、作废 replay 计数 */
    public synchronized void halt() {
        replayActive = false;
        replayOutstanding = 0;
        replayLastSeg = -1;
        stopPlaybackLocked();
        settleInFlightLocked();
        drainDeadLocked();          // 缓冲全部按读完结算并清空（旧故事的段不需要再读）
        reportSuppressedLocked();
    }

    /* ================= 内部（持锁） ================= */

    private SegRec rec(int seg) {
        SegRec r = segs.get(seg);
        if (r == null) { r = new SegRec(); segs.put(seg, r); }
        return r;
    }

    private void removeReplayItemsLocked() {
        Iterator<Item> it = undelivered.iterator();
        while (it.hasNext()) if (it.next().replay) it.remove();
    }

    /** 已交给 TTS 未结算的句按完成记账；结算完清 finished 防无界增长 */
    private void settleInFlightLocked() {
        for (String id : new ArrayList<>(inFlight)) finishLocked(id);
        if (inFlight.isEmpty()) { finished.clear(); started.clear(); }
    }

    /** 全部按读完记账：在途 + 缓冲；之后 seal/replay 立即完成 */
    private void drainDeadLocked() {
        settleInFlightLocked();
        Iterator<Item> it = undelivered.iterator();
        while (it.hasNext()) {
            Item i = it.next();
            it.remove();
            if (i.replay) replayOutstanding--;
            else { SegRec r = rec(i.seg); r.done++; reportLocked(i.seg); }
        }
        checkReplayDoneLocked();
    }

    private void pumpLocked() {
        if (st != ST_OK || undelivered.isEmpty()) return;
        if (!useDevice && tts == null) return;
        Iterator<Item> it = undelivered.iterator();
        while (it.hasNext()) {
            Item i = it.next();
            String id = (i.replay ? "r" : "g") + "#" + i.seg + "#" + (seq++);
            it.remove();
            idSeg.put(id, i.seg);
            idText.put(id, i.text);
            inFlight.add(id);
            if (useDevice) {
                try { deviceTts.speak(id, i.text); }
                catch (Exception e) { degradeLocked("device speak rejected: " + e.getMessage()); return; }
            } else {
                int rc;
                try { rc = tts.speak(i.text, TextToSpeech.QUEUE_ADD, null, id); }
                catch (Exception e) { rc = TextToSpeech.ERROR; }
                if (rc == TextToSpeech.ERROR) { degradeLocked("speak rejected"); return; }
            }
        }
        // 队列级看门狗：只在队列非空时轮询，按"整体进度"判引擎死活——
        // 单句从入队计时会被排在后面的句误伤（QUEUE_ADD 排队不是故障）
        if (lastProgressAt == 0) lastProgressAt = System.currentTimeMillis();
        main.removeCallbacks(queueWatch);
        main.postDelayed(queueWatch, 3_000);
    }

    /** 开读后 45s 没读完：引擎卡死 → 降级（中文单句远短于此） */
    private void watchStuck(String id) {
        main.postDelayed(() -> {
            synchronized (TtsPlayer.this) {
                if (st == ST_OK && inFlight.contains(id) && !finished.contains(id)) {
                    degradeLocked("engine stuck 45s on " + id);
                }
            }
        }, STUCK_MS);
    }

    private void degradeLocked(String why) {
        if (st == ST_DEAD) return;
        Log.w(TAG, "degrade: " + why);
        st = ST_DEAD;
        drainDeadLocked();
        // 引擎侧只做无锁事件投递，不会回调进 player
        listener.onDegraded(why);
    }

    private void finishLocked(String id) {
        if (finished.contains(id)) return;
        finished.add(id);
        inFlight.remove(id);
        started.remove(id);
        lastProgressAt = System.currentTimeMillis();   // 队列有结算=引擎活着
        Integer segObj = idSeg.remove(id);
        idText.remove(id);
        if (segObj == null) return;
        if (id.startsWith("r")) {
            if (replayOutstanding > 0) replayOutstanding--;
            checkReplayDoneLocked();
        } else {
            SegRec r = rec(segObj);
            r.done++;
            reportLocked(segObj);
        }
    }

    private void checkReplayDoneLocked() {
        if (replayActive && replayOutstanding <= 0) {
            replayActive = false;
            listener.onReplayDone(replayLastSeg);
            reportSuppressedLocked();   // 重听期间压住的段读完补发
        }
    }

    private void reportLocked(int seg) {
        SegRec r = rec(seg);
        if (r.reported || !r.sealed || r.done < r.enqueued) return;
        if (replayActive) return;   // 重听期间压制，结束后 reportSuppressedLocked 补发
        r.reported = true;
        listener.onSegmentRead(seg);
    }

    private void reportSuppressedLocked() {
        if (replayActive) return;
        for (Map.Entry<Integer, SegRec> e : segs.entrySet()) reportLocked(e.getKey());
    }

    /* ================= TTS 回调 ================= */

    @Override
    public void onInit(int code) {
        synchronized (this) {
            if (st == ST_DEAD || useDevice || deviceSwitching) return;   // 已降级/已切端上：迟到回调作废
            if (code != TextToSpeech.SUCCESS) { failOrDeviceLocked("init failed code=" + code); return; }
            tts.setOnUtteranceProgressListener(this);
            try { tts.setSpeechRate(rate); } catch (Exception ignored) { }
            int lang;
            try { lang = tts.setLanguage(Locale.CHINA); }
            catch (Exception e) { failOrDeviceLocked("setLanguage: " + e); return; }
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 用户手机正是这种：系统引擎在、但没有中文（M8）
                failOrDeviceLocked("no zh lang (code=" + lang + ")");
                return;
            }
            boolean zh = probeZh();
            if (!zh && lang != TextToSpeech.LANG_AVAILABLE) { failOrDeviceLocked("no zh voice"); return; }
            Log.i(TAG, "ready lang=" + lang + " zhVoices=" + zh);
            st = ST_OK;
            applyVoiceLocked();   // 启动前存的音色此刻生效
            pumpLocked();
        }
    }

    private boolean probeZh() {
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs == null || vs.isEmpty()) return true;   // 引擎不报清单时放行，看门狗兜底
            for (Voice v : vs) {
                Locale l = v.getLocale();
                if (l != null && "zh".equals(l.getLanguage())) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void onStart(String utteranceId) {
        synchronized (this) {
            lastProgressAt = System.currentTimeMillis();
            if (finished.contains(utteranceId)) return;
            started.add(utteranceId);
            watchStuck(utteranceId);
            String text = idText.get(utteranceId);
            Integer seg = idSeg.get(utteranceId);
            if (text != null && seg != null) listener.onSpeaking(seg, text);
        }
    }

    @Override
    public void onDone(String utteranceId) {
        synchronized (this) { finishLocked(utteranceId); }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onError(String utteranceId) {
        synchronized (this) { finishLocked(utteranceId); }
    }

    @Override
    public void onError(String utteranceId, int errorCode) {
        synchronized (this) { finishLocked(utteranceId); }
    }
}
