package org.offblink.reverie;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 设置存储：SharedPreferences。
 * 红线：apiKey 只存在这里（原生域），永不经桥下发给 JS（SPEC §4/§9）。
 */
public class Settings {

    private static final String PREF = "reverie_settings";
    private static final String K_ENDPOINT = "endpoint";
    private static final String K_API_KEY = "apiKey";
    private static final String K_CHAT_MODEL = "chatModel";
    private static final String K_ASR_MODEL = "asrModel";
    private static final String K_ASR_MODE = "asrMode";
    private static final String K_ASR_ENDPOINT = "asrEndpoint";
    private static final String K_ASR_KEY = "asrApiKey";
    private static final String K_TTS_VOICE = "ttsVoice";
    private static final String K_TTS_RATE = "ttsRate";
    private static final String K_MIC_WINDOW = "micWindowSec";

    // 默认值：OpenAI 官方口径，用户可全改
    public static final String DEF_ENDPOINT = "https://api.openai.com/v1";
    public static final String DEF_CHAT_MODEL = "gpt-4o-mini";
    public static final String DEF_ASR_MODEL = "whisper-1";
    public static final String DEF_TTS_VOICE = "";
    public static final double DEF_TTS_RATE = 0.9;
    public static final int DEF_MIC_WINDOW_SEC = 10;

    private final SharedPreferences sp;

    public Settings(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String endpoint() { return sp.getString(K_ENDPOINT, DEF_ENDPOINT); }
    public String apiKey() { return sp.getString(K_API_KEY, ""); }
    public String chatModel() { return sp.getString(K_CHAT_MODEL, DEF_CHAT_MODEL); }
    public String asrModel() { return sp.getString(K_ASR_MODEL, DEF_ASR_MODEL); }

    /** "device"=端上模型（默认，M7）| "cloud"=云端 whisper */
    public String asrMode() { return sp.getString(K_ASR_MODE, "device"); }

    /** ASR 独立端点：空 = 跟随 LLM endpoint（M6 拍板：MiMo 网关无 transcriptions） */
    public String asrEndpoint() { return sp.getString(K_ASR_ENDPOINT, ""); }

    /** ASR 独立 key：空 = 跟随 LLM apiKey */
    public String asrApiKey() { return sp.getString(K_ASR_KEY, ""); }

    public String ttsVoice() { return sp.getString(K_TTS_VOICE, DEF_TTS_VOICE); }
    public float ttsRate() { return sp.getFloat(K_TTS_RATE, (float) DEF_TTS_RATE); }
    public int micWindowSec() { return sp.getInt(K_MIC_WINDOW, DEF_MIC_WINDOW_SEC); }

    /** JS 下发的 patch 应用：只覆盖出现的键；apiKey 空串视为不改 */
    public void apply(JSONObject patch) throws JSONException {
        SharedPreferences.Editor e = sp.edit();
        if (patch.has("endpoint")) e.putString(K_ENDPOINT, patch.getString("endpoint"));
        if (patch.has("apiKey")) {
            String k = patch.optString("apiKey", "");
            if (!k.isEmpty()) e.putString(K_API_KEY, k);
        }
        if (patch.has("chatModel")) e.putString(K_CHAT_MODEL, patch.getString("chatModel"));
        if (patch.has("asrModel")) e.putString(K_ASR_MODEL, patch.getString("asrModel"));
        if (patch.has("asrMode")) e.putString(K_ASR_MODE, "cloud".equals(patch.optString("asrMode")) ? "cloud" : "device");
        // asrEndpoint 允许写空串（= 回落跟随 LLM）；asrApiKey 与主 apiKey 同口径：空 = 不改
        if (patch.has("asrEndpoint")) e.putString(K_ASR_ENDPOINT, patch.optString("asrEndpoint", ""));
        if (patch.has("asrApiKey")) {
            String k = patch.optString("asrApiKey", "");
            if (!k.isEmpty()) e.putString(K_ASR_KEY, k);
        }
        if (patch.has("ttsVoice")) e.putString(K_TTS_VOICE, patch.optString("ttsVoice", ""));
        if (patch.has("ttsRate")) {
            double r = patch.optDouble("ttsRate", DEF_TTS_RATE);
            e.putFloat(K_TTS_RATE, (float) Math.max(0.7, Math.min(1.1, r)));
        }
        if (patch.has("micWindowSec")) {
            int s = patch.optInt("micWindowSec", DEF_MIC_WINDOW_SEC);
            e.putInt(K_MIC_WINDOW, Math.max(3, Math.min(30, s)));
        }
        e.apply();
    }

    /** 给 JS 的设置视图：不含 apiKey，只有 hasKey 布尔 */
    public JSONObject publicView() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("endpoint", endpoint());
        o.put("hasKey", !apiKey().isEmpty());
        o.put("chatModel", chatModel());
        o.put("asrModel", asrModel());
        o.put("asrMode", asrMode());
        o.put("asrEndpoint", asrEndpoint());
        o.put("hasAsrKey", !asrApiKey().isEmpty());
        o.put("ttsVoice", ttsVoice());
        o.put("ttsRate", ttsRate());
        o.put("micWindowSec", micWindowSec());
        return o;
    }
}
