package com.campusbuddies;

import com.campusbuddies.ai.AiClient;
import com.campusbuddies.ai.RuleBasedAiClient;
import com.campusbuddies.match.MatchService;
import com.campusbuddies.match.TagNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同义词归一与本地规则版“智能填写 / 违规初筛”的单元测试（不需要启动 Spring，也不联网）。 */
class AiAssistTest {
  private final AiClient rules = new RuleBasedAiClient();

  @Test
  @DisplayName("QR-M5 不同说法的同一兴趣归一后能匹配上（打羽毛球 / 羽球 / badminton → 羽毛球）")
  void synonymsMatch() {
    assertEquals("羽毛球", TagNormalizer.normalize("打羽毛球"));
    assertEquals("羽毛球", TagNormalizer.normalize(" Badminton "));
    assertEquals("冲浪", TagNormalizer.normalize("冲浪"));
    List<Object> slot = List.of(Map.of("day", "周六", "start", "14:00", "end", "16:00"));
    MatchService.Person me = new MatchService.Person(MatchService.lower(List.of("打羽毛球")), Set.of(), Set.of());
    MatchService.Person them = new MatchService.Person(MatchService.lower(List.of("羽球")), Set.of(), Set.of());
    // 兴趣完全相同：35 分 + 共同 2 小时：30 分
    assertEquals(65, (int) MatchService.score(me, slot, Set.of(), them, List.of(slot), Set.of()).score());
  }

  @Test
  @DisplayName("QR-A1 智能填写：一句话拆出类型、标签、星期和时间段")
  void draftFromSentence() {
    AiClient.NeedDraft d = rules.draftNeed("这周六下午三点到五点想找人打羽毛球，水平一般");
    assertEquals("运动", d.category());
    assertTrue(d.tags().contains("羽毛球"));
    assertEquals("周六", d.day());
    assertEquals("15:00", d.start()); // 中文数字“三点到五点”+“下午” → 15:00–17:00
    assertEquals("17:00", d.end());
    assertEquals("rules", d.source());
    AiClient.NeedDraft cn = rules.draftNeed("这周六下午一到三点想找人打羽毛球，技术一般");
    assertEquals("13:00", cn.start());
    assertEquals("15:00", cn.end());
    AiClient.NeedDraft half = rules.draftNeed("周日晚上七点半到九点跑步");
    assertEquals("19:30", half.start());
    assertEquals("21:00", half.end());
    AiClient.NeedDraft vague = rules.draftNeed("周六下午想打羽毛球");
    assertEquals("14:00", vague.start()); // 没说具体几点时，按“下午”给出常用时段 14:00–16:00
    AiClient.NeedDraft exact = rules.draftNeed("周三晚上7点到9点图书馆自习");
    assertEquals("学习", exact.category());
    assertEquals("19:00", exact.start());
    assertEquals("21:00", exact.end());
  }

  @Test
  @DisplayName("QR-A2 智能填写识别不出的字段留空，由用户自己填")
  void unknownFieldsLeftBlank() {
    AiClient.NeedDraft d = rules.draftNeed("随便聊聊");
    assertEquals("", d.category());
    assertEquals("", d.day());
    assertEquals("", d.start());
  }

  @Test
  @DisplayName("QR-A3 违规初筛：含代写等敏感词的帖子被标记，正常帖子不标记")
  void moderation() {
    assertTrue(rules.moderate("有偿代写课程作业", "价格私聊").flagged());
    assertFalse(rules.moderate("周六羽毛球搭子", "体育馆 3 号场").flagged());
  }
}
