package org.offblink.reverie.engine;

import android.util.Log;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断环形日志（M10）：关键路径数值落这里，设置页「诊断」面板可看（bridge getDiag）。
 * 目的：真机上「句间隔大」「麦克风听不到」这类只在设备上复现的问题，靠设备侧日志自证，
 * 不用连 adb。同时写 logcat（tag reverie-diag）。
 */
public final class Diag {

    private static final int MAX = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Diag() {}

    public static synchronized void add(String tag, String msg) {
        String line = FMT.format(new Date()) + " [" + tag + "] " + msg;
        if (LINES.size() >= MAX) LINES.removeFirst();
        LINES.addLast(line);
        Log.i("reverie-diag", line);
    }

    /** 最近 n 条（JSON 字符串数组） */
    public static synchronized String dump(int n) {
        JSONArray arr = new JSONArray();
        int skip = Math.max(0, LINES.size() - n);
        int i = 0;
        for (String s : LINES) {
            if (i++ >= skip) arr.put(s);
        }
        return arr.toString();
    }
}
