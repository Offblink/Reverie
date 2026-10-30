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
