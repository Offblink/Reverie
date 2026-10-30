package org.offblink.reverie.engine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 故事数据模型（SPEC §4）。一个互动单元 = 一段正文 + 2–4 个选项；
 * 结局段 isFinal=true 且 options 空。
 */
public class Story {

    public static class Segment {
        public int index;
        public String text = "";
        public List<String> options = new ArrayList<>();
        public int choice = -1;          // -1=未选；0..n=用户选的下标
        public String heard = null;      // 语音识别原文（M5）
        public boolean isFinal = false;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("index", index);
            o.put("text", text);
            o.put("options", new JSONArray(options));
            o.put("choice", choice);
            o.put("heard", heard == null ? JSONObject.NULL : heard);
            o.put("isFinal", isFinal);
            return o;
        }

        public static Segment fromJson(JSONObject o) throws JSONException {
            Segment s = new Segment();
            s.index = o.optInt("index");
            s.text = o.optString("text", "");
            JSONArray opts = o.optJSONArray("options");
            if (opts != null) {
                for (int i = 0; i < opts.length(); i++) s.options.add(opts.getString(i));
            }
            s.choice = o.optInt("choice", -1);
            s.heard = o.has("heard") && !o.isNull("heard") ? o.optString("heard") : null;
            s.isFinal = o.optBoolean("isFinal", false);
            return s;
        }
    }

    public String id;
    public String title = "";
    public String themeId;
    public String themeName;
    public String tier;                 // short | medium | long
    public int choicesTarget;
    public String synopsis = "";
    public long created;
    public long updated;
    public String status = "active";    // active | done
    public final List<Segment> segments = new ArrayList<>();

    /** 最后一个段（生成中的占位不在列表里——列表只放已落定的段） */
    public Segment last() { return segments.isEmpty() ? null : segments.get(segments.size() - 1); }

    /** 未回答的选择点（续听落点）：最后一段有选项且未选 */
    public Segment pendingChoice() {
        Segment s = last();
        return (s != null && !s.isFinal && s.choice == -1 && !s.options.isEmpty()) ? s : null;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("themeId", themeId);
        o.put("themeName", themeName);
        o.put("tier", tier);
        o.put("choicesTarget", choicesTarget);
        o.put("synopsis", synopsis);
        o.put("created", created);
        o.put("updated", updated);
        o.put("status", status);
        JSONArray arr = new JSONArray();
        for (Segment s : segments) arr.put(s.toJson());
        o.put("segments", arr);
        return o;
    }

    public static Story fromJson(JSONObject o) throws JSONException {
        Story st = new Story();
        st.id = o.getString("id");
        st.title = o.optString("title", "");
        st.themeId = o.optString("themeId");
        st.themeName = o.optString("themeName", st.themeId);
        st.tier = o.optString("tier");
        st.choicesTarget = o.optInt("choicesTarget", 10);
        st.synopsis = o.optString("synopsis", "");
        st.created = o.optLong("created");
        st.updated = o.optLong("updated");
        st.status = o.optString("status", "active");
        JSONArray arr = o.optJSONArray("segments");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) st.segments.add(Segment.fromJson(arr.getJSONObject(i)));
        }
        return st;
    }

    /** 给 JS 的全量快照（onState / getState 用） */
    public JSONObject toState() throws JSONException {
        JSONObject o = toJson();
        o.put("progress", (pendingChoice() != null || status.equals("done"))
                ? segments.size() + "/" + choicesTarget : segments.size() + "/" + choicesTarget);
        return o;
    }
}
