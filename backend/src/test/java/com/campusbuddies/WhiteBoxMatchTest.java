package com.campusbuddies;

import com.campusbuddies.match.MatchService;
import com.campusbuddies.match.MatchService.Person;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 白盒测试（第 7 章）：依据 MatchService.score / overlapMinutes 的内部控制流程设计用例，
 * 做到每个判断的“真 / 假”分支都至少执行一次（分支覆盖），并覆盖基本路径。
 *
 * score 的判断点：
 *   D1 共同空闲时间是否为 0（为 0 直接返回不推荐）
 *   D2 共同兴趣是否为空        D3 主题契合是否为空        D4 技能互补是否为空
 *   D5 兴趣并集是否为空（防止除以 0）   D6 需求标签是否为空（防止除以 0）
 * overlapMinutes 的判断点：
 *   E1 两段是否同一天        E2 时间格式是否合法（不合法跳过）
 * 已有的 MatchServiceTest 覆盖了 D1 真、D2~D4 全真、D4 真且 D2/D3 假、E2 假；本类补足其余分支。
 */
class WhiteBoxMatchTest {
  private static final List<Object> SAT_14_16 = List.of(Map.of("day", "周六", "start", "14:00", "end", "16:00"));
  private static final Person EMPTY = new Person(Set.of(), Set.of(), Set.of());

  @Test
  @DisplayName("WB-1 路径：D1 假，D2/D3/D4 均假，D5/D6 为空集 —— 只得时间分 30，理由只有 1 条，不出现除以 0")
  void onlyTimePath() {
    MatchService.Result r = MatchService.score(EMPTY, SAT_14_16, Set.of(), EMPTY, List.of(SAT_14_16), Set.of());
    assertEquals(30, (int) r.score());
    assertEquals(1, r.reasons().size());
  }

  @Test
  @DisplayName("WB-2 路径：D2 真、D3/D4 假 —— 兴趣 1/2 相同得 17.5 分，加时间 30 分，四舍五入为 48")
  void sharedInterestOnlyPath() {
    Person me = new Person(Set.of("羽毛球", "摄影"), Set.of(), Set.of());
    Person them = new Person(Set.of("羽毛球"), Set.of(), Set.of());
    MatchService.Result r = MatchService.score(me, SAT_14_16, Set.of(), them, List.of(SAT_14_16), Set.of());
    assertEquals(48, (int) r.score());
    assertTrue(r.reasons().get(0).startsWith("共同兴趣"));
  }

  @Test
  @DisplayName("WB-3 路径：D3 真、D2/D4 假 —— 需求 2 个标签被覆盖 1 个得 10 分，加时间 30 分")
  void topicOnlyPath() {
    MatchService.Result r = MatchService.score(EMPTY, SAT_14_16, Set.of("羽毛球", "运动"), EMPTY, List.of(SAT_14_16), Set.of("羽毛球"));
    assertEquals(40, (int) r.score());
    assertTrue(r.reasons().stream().anyMatch(x -> x.startsWith("主题契合")));
  }

  @Test
  @DisplayName("WB-4 分支：E1 假（不同天）跳过；对方有多条需求时取重叠最多的一条")
  void differentDayAndBestOfMany() {
    List<Object> sunday = List.of(Map.of("day", "周日", "start", "14:00", "end", "16:00"));
    List<Object> sat15to16 = List.of(Map.of("day", "周六", "start", "15:00", "end", "16:00"));
    assertEquals(0, MatchService.overlapMinutes(SAT_14_16, sunday));
    MatchService.Result r = MatchService.score(EMPTY, SAT_14_16, Set.of(), EMPTY, List.of(sunday, sat15to16, SAT_14_16), Set.of());
    assertEquals(30, (int) r.score());
  }

  @Test
  @DisplayName("WB-5 边界：共同时间超过 2 小时时时间分封顶 30，不会超过总分 100")
  void timeScoreCapped() {
    List<Object> long6h = List.of(Map.of("day", "周六", "start", "10:00", "end", "16:00"));
    MatchService.Result r = MatchService.score(EMPTY, long6h, Set.of(), EMPTY, List.of(long6h), Set.of());
    assertEquals(30, (int) r.score());
  }
}
