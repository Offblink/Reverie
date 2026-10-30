package org.offblink.reverie.engine;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 故事持久化：filesDir/stories/<id>.json，一书一份。
 * 写入 = 临时文件 + rename（崩溃安全，SPEC §4）。
 */
public class StoryStore {

    private final File dir;

    public StoryStore(Context ctx) {
        dir = new File(ctx.getApplicationContext().getFilesDir(), "stories");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public void save(Story s) throws Exception {
        s.updated = System.currentTimeMillis();
        File tmp = new File(dir, s.id + ".json.tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(s.toJson().toString());
        }
        File dst = new File(dir, s.id + ".json");
        if (!dst.delete() && dst.exists()) throw new IllegalStateException("cannot replace " + dst);
        if (!tmp.renameTo(dst)) throw new IllegalStateException("rename failed " + tmp);
    }

    public Story load(String id) {
        File f = new File(dir, id + ".json");
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return Story.fromJson(new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;   // 坏文件不炸，当不存在
        }
    }

    public List<Story> list() {
        List<Story> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        for (File f : fs) {
            Story s = load(f.getName().replaceAll("\\.json$", ""));
            if (s != null) out.add(s);
        }
        out.sort((a, b) -> Long.compare(b.updated, a.updated));   // 最近的在前
        return out;
    }

    public void delete(String id) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".json").delete();
    }
}
