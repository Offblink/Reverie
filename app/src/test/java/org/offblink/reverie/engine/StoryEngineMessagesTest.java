package org.offblink.reverie.engine;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** 人称注入（M8）：pov 决定 system prompt 的叙述视角；选项永远保持第二人称指令口吻 */
public class StoryEngineMessagesTest {

    private static Story story(String pov) {
        Story s = new Story();
        s.id = "1";
        s.themeId = "healing";
        s.themeName = "治愈";
        s.tier = "short";
        s.pov = pov;
        s.choicesTarget = 10;
        return s;
    }

    private static String systemOf(Story s) {
        List<String[]> msgs = StoryEngine.buildMessages(s, 0);
        return msgs.get(0)[1];
    }

    @Test
    public void firstPersonUsesWo() {
        String sys = systemOf(story("first"));
        assertTrue(sys.contains("第一人称"));
        assertTrue(sys.contains("「我」"));
    }

    @Test
    public void thirdPersonForbidsNiForProtagonist() {
        String sys = systemOf(story("third"));
        assertTrue(sys.contains("第三人称"));
        assertTrue(sys.contains("勿用「你」"));
    }

    @Test
    public void optionsStaySecondPersonRegardlessOfPov() {
        // 选项是对听众的指令，不能跟正文一起变第一人称
        for (String pov : new String[]{"first", "third"}) {
            assertTrue(StoryEngine.buildMessages(story(pov), 0).get(0)[1].contains("options 是给听众的指令"));
        }
    }

    @Test
    public void unknownPovFallsBackToThird() {
        // 旧档/脏值：Story.fromJson 已归一，但 newStory 入口也兜底——这里钉 prompt 侧按 third 渲染
        Story s = story("third");
        assertTrue(systemOf(s).contains("第三人称"));
    }

    @Test
    public void optionsPromptReadsNumberedChoices() {
        // M9：选项也要读——「请选择。一，推开门。二，离开。」
        java.util.List<String> opts = java.util.Arrays.asList("推开门看看", "转身离开");
        assertTrue(StoryEngine.optionsPrompt(opts)
                .equals("请选择。一，推开门看看。二，转身离开。"));
    }

    @Test
    public void optionsPromptEmptyAndDegenerate() {
        assertTrue(StoryEngine.optionsPrompt(null).isEmpty());
        assertTrue(StoryEngine.optionsPrompt(java.util.Collections.emptyList()).isEmpty());
        // 全空串选项 → 视为无选项
        assertTrue(StoryEngine.optionsPrompt(java.util.Arrays.asList("  ", "")).isEmpty());
    }

    @Test
    public void optionsPromptCapsAtFive() {
        java.util.List<String> five = java.util.Arrays.asList("a", "b", "c", "d", "e", "f", "g");
        String p = StoryEngine.optionsPrompt(five);
        assertTrue(p.contains("五，e。"));
        assertTrue(!p.contains("f"));
    }
}
