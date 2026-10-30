package org.offblink.reverie.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** WAV→float 采样转换（端上识别的进料口；头解析错 = 识别全空或越界） */
public class DeviceAsrTest {

    private static byte[] wavOf(short... pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()); b.putInt(36 + pcm.length * 2); b.put("WAVE".getBytes());
        b.put("fmt ".getBytes()); b.putInt(16); b.putShort((short) 1); b.putShort((short) 1);
        b.putInt(16000); b.putInt(32000); b.putShort((short) 2); b.putShort((short) 16);
        b.put("data".getBytes()); b.putInt(pcm.length * 2);
        for (short s : pcm) b.putShort(s);
        return b.array();
    }

    @Test
    public void convertsPcmAndSkipsHeader() {
        float[] out = DeviceAsr.wavToFloat(wavOf((short) 0, (short) 16384, (short) -16384, (short) 32767));
        assertEquals(4, out.length);
        assertEquals(0f, out[0], 1e-6);
        assertEquals(0.5f, out[1], 1e-3);
        assertEquals(-0.5f, out[2], 1e-3);
        assertEquals(1.0f, out[3], 1e-3);
    }

    @Test
    public void toleratesTruncatedTail() {
        // data 声明 4 个样本但尾巴缺 1 字节：按整样本截断，不越界
        byte[] wav = wavOf((short) 1000, (short) 2000, (short) 3000, (short) 4000);
        byte[] cut = java.util.Arrays.copyOf(wav, wav.length - 1);
        float[] out = DeviceAsr.wavToFloat(cut);
        assertEquals(3, out.length);
    }
}
