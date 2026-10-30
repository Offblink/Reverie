package org.offblink.reverie.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * PCM→WAV 封装（16k/mono/16-bit，44 字节头）。头错了 whisper 直接拒收整段录音。
 * wrapWav 是纯静态函数，不触碰任何 android.* 运行时。
 */
public class AsrRecorderTest {

    @Test
    public void wavHeaderIsWellFormed() {
        byte[] pcm = new byte[3200];   // 100ms 的 16k mono 16-bit
        byte[] wav = AsrRecorder.wrapWav(pcm);

        assertEquals(44 + pcm.length, wav.length);
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        byte[] riff = new byte[4];
        b.get(riff);
        assertEquals("RIFF", new String(riff, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(36 + pcm.length, b.getInt());          // RIFF 尺寸
        byte[] wave = new byte[4];
        b.get(wave);
        assertEquals("WAVE", new String(wave, java.nio.charset.StandardCharsets.US_ASCII));
        byte[] fmt = new byte[4];
        b.get(fmt);
        assertEquals("fmt ", new String(fmt, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(16, b.getInt());                        // fmt 块长
        assertEquals(1, b.getShort());                       // PCM
        assertEquals(1, b.getShort());                       // mono
        assertEquals(16000, b.getInt());                     // 采样率
        assertEquals(32000, b.getInt());                     // 字节率
        assertEquals(2, b.getShort());                       // block align
        assertEquals(16, b.getShort());                      // 位深
        byte[] data = new byte[4];
        b.get(data);
        assertEquals("data", new String(data, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(pcm.length, b.getInt());                // data 尺寸
    }
}
