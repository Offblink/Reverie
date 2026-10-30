package org.offblink.reverie.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 语音选择匹配规则（SPEC §7）：序号词 → 关键词包含 → 相似度 ≥0.5 → 未命中。
 * 选错分支 = 故事走错方向，这些断言钉住每个判定路径。
 */
public class OptionMatcherTest {

    private static final List<String> OPTS = Arrays.asList(
            "抄起木棍，朝东厢走去",
            "先点亮堂屋所有的灯",
            "翻墙出去找邻居帮忙");

    @Test
    public void ordinalPhraseMapsToIndex() {
        assertEquals(0, OptionMatcher.match("选第一个", OPTS));
        assertEquals(1, OptionMatcher.match("第二", OPTS));
        assertEquals(2, OptionMatcher.match("3", OPTS));
        assertEquals(2, OptionMatcher.match("我选第三个。", OPTS));
    }

    @Test
    public void outOfRangeOrdinalFallsThrough() {
        assertEquals(-1, OptionMatcher.match("选第五个", OPTS));   // 越界序号不当命中
    }

    @Test
    public void keywordContainmentWins() {
        assertEquals(0, OptionMatcher.match("东厢", OPTS));
    }

    @Test
    public void similarityAtThresholdMatches() {
        assertEquals(1, OptionMatcher.match("点亮堂屋", OPTS));    // dice=0.6
    }

    @Test
    public void unrelatedSpeechMisses() {
        assertEquals(-1, OptionMatcher.match("飞向太空", OPTS));
        assertEquals(-1, OptionMatcher.match("", OPTS));
        assertEquals(-1, OptionMatcher.match(null, OPTS));
        assertEquals(-1, OptionMatcher.match("嗯…", OPTS));
    }

    @Test
    public void normalizeStripsFillers() {
        assertEquals("先点亮堂屋所有灯", OptionMatcher.normalize("先点亮堂屋所有的灯"));
        assertEquals("", OptionMatcher.normalize("的了吗啊！ "));
    }
}
