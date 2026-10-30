package org.offblink.reverie;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 设置存储：SharedPreferences。
 * 红线：apiKey 只存在这里（原生域），永不经桥下发给 JS（SPEC §4/§9）。
 *
 * M12 起只剩 LLM 三项（endpoint/apiKey/chatModel）——ASR 与 TTS 相关键随语音链路一并删除。
 */
public class Settings {

    private static final String PREF = "reverie_settings";
    private static final String K_ENDPOINT = "endpoint";
    private static final String K_API_KEY = "apiKey";
    private static final String K_CHAT_MODEL = "chatModel";

    // 默认值：OpenAI 官方口径，用户可全改
    public static final String DEF_ENDPOINT = "https://api.openai.com/v1";
    public static final String DEF_CHAT_MODEL = "gpt-4o-mini";

    private final SharedPreferences sp;

    public Settings(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String endpoint() { return sp.getString(K_ENDPOINT, DEF_ENDPOINT); }
    public String apiKey() { return sp.getString(K_API_KEY, ""); }
    public String chatModel() { return sp.getString(K_CHAT_MODEL, DEF_CHAT_MODEL); }

    /** JS 下发的 patch 应用：只覆盖出现的键；apiKey 空串视为不改 */
    public void apply(JSONObject patch) throws JSONException {
        SharedPreferences.Editor e = sp.edit();
        // 端点在保存时就归一化（去换行/补协议/剥重复路径），设置页回显修正后的值
        if (patch.has("endpoint")) {
            e.putString(K_ENDPOINT, org.offblink.reverie.engine.LlmClient.normalizeBase(patch.optString("endpoint")));
        }
        if (patch.has("apiKey")) {
            String k = patch.optString("apiKey", "").replaceAll("[\\r\\n\\t]", "").trim();
            if (!k.isEmpty()) e.putString(K_API_KEY, k);
        }
        if (patch.has("chatModel")) e.putString(K_CHAT_MODEL, patch.getString("chatModel").trim());
        e.apply();
    }

    /** 给 JS 的设置视图：不含 apiKey，只有 hasKey 布尔 */
    public JSONObject publicView() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("endpoint", endpoint());
        o.put("hasKey", !apiKey().isEmpty());
        o.put("chatModel", chatModel());
        return o;
    }
}
