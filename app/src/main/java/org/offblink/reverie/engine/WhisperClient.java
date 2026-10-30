package org.offblink.reverie.engine;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * whisper 云识别（SPEC §7）：POST {endpoint}/audio/transcriptions，
 * 与 LLM 同一 baseURL/apiKey。multipart：file(audio.wav) + model + language=zh。
 * 异步（enqueue），回调在 OkHttp 线程——调用方自己 hop 线程。
 */
public class WhisperClient {

    public interface Listener {
        void onText(String text);     // 识别文本（已 trim，可能为空串）
        void onError(String msg);     // 网络/协议失败
    }

    private static final MediaType WAV_T = MediaType.get("audio/wav");
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public void transcribe(String baseUrl, String apiKey, String model, byte[] wav, Listener l) {
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("language", "zh")
                .addFormDataPart("response_format", "json")
                .addFormDataPart("file", "speech.wav", RequestBody.create(wav, WAV_T))
                .build();

        String url = LlmClient.normalizeBase(baseUrl) + "/audio/transcriptions";
        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(body);
        if (!apiKey.isEmpty()) rb.header("Authorization", "Bearer " + apiKey);

        http.newCall(rb.build()).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call c, IOException e) {
                if (!c.isCanceled()) l.onError("网络失败 POST " + url + ": " + e.getMessage());
            }

            @Override public void onResponse(Call c, Response resp) {
                try (Response r = resp) {
                    if (!r.isSuccessful()) {
                        String err = r.body() != null ? r.body().string() : "";
                        l.onError(LlmClient.httpError(r.code(), url, err));
                        return;
                    }
                    if (r.body() == null) { l.onError("空响应体"); return; }
                    JSONObject o = new JSONObject(r.body().string());
                    l.onText(o.optString("text", "").trim());
                } catch (JSONException e) {
                    l.onError("解析失败: " + e.getMessage());
                } catch (Exception e) {
                    l.onError("读响应失败: " + e.getMessage());
                }
            }
        });
    }
}
