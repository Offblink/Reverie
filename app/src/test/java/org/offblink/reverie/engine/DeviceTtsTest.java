package org.offblink.reverie.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 端上 TTS 音频出料口：float→PCM16 是 wavToFloat 的逆变换，钳位防削波爆音 */
public class DeviceTtsTest {

    @Test
    public void roundTripsAgainstWavToFloat() {
        float[] in = { 0f, 0.5f, -0.5f, 1f, -1f };
        short[] pcm = DeviceTts.floatToPcm16(in);
        assertEquals(5, pcm.length);
        // 造 WAV 头包回去再走 wavToFloat，误差应 < 1/32767
        byte[] wav = wavOf(pcm);
        float[] back = DeviceAsr.wavToFloat(wav);
        for (int i = 0; i < in.length; i++) {
            assertEquals("idx " + i, in[i], back[i], 1e-3);
        }
    }

    @Test
    public void clampsOutOfRangeInsteadOfWrapping() {
        short[] pcm = DeviceTts.floatToPcm16(new float[]{ 2f, -2f });
        assertEquals(Short.MAX_VALUE, pcm[0]);        // 不钳位会绕回 -32768 = 爆音
        assertEquals(-Short.MAX_VALUE, pcm[1]);
    }

    @Test
    public void voiceNameToSidIsSingleVitsSpeaker() {
        // fanchen-wnj：单说话人成年男声，sid 恒 0（M11）
        assertEquals(1, DeviceTts.VOICE_NAMES.length);
        assertEquals(0, DeviceTts.DEFAULT_SID);
        assertEquals(0, DeviceTts.sidForName("wnj_male"));
        // 旧 Kokoro 音色名（设置里可能还存着）→ 查不到 → 回落默认男声
        assertEquals(-1, DeviceTts.sidForName("zm_010"));
        assertEquals(-1, DeviceTts.sidForName("zf_001"));
        assertEquals(-1, DeviceTts.sidForName("nope"));
        assertEquals(-1, DeviceTts.sidForName(null));
        assertEquals(-1, DeviceTts.sidForName(""));
    }

    @Test
    public void zhVoiceNamesListsTheOneSpeaker() {
        java.util.List<String> v = DeviceTts.zhVoiceNames();
        assertEquals(1, v.size());
        assertEquals("wnj_male", v.get(0));
    }

    @Test
    public void padTailAppendsSilence() {
        float[] in = new float[100];
        float[] out = DeviceTts.padTail(in, 24000);
        assertEquals(100 + 3600, out.length);        // 0.15s @ 24kHz（M9 实测：0.45s 会被听成句间隔过大）
        assertEquals(0f, out[out.length - 1], 1e-9);
    }

    private static byte[] wavOf(short[] pcm) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + pcm.length * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()); b.putInt(36 + pcm.length * 2); b.put("WAVE".getBytes());
        b.put("fmt ".getBytes()); b.putInt(16); b.putShort((short) 1); b.putShort((short) 1);
        b.putInt(16000); b.putInt(32000); b.putShort((short) 2); b.putShort((short) 16);
        b.put("data".getBytes()); b.putInt(pcm.length * 2);
        for (short s : pcm) b.putShort(s);
        return b.array();
    }
}
