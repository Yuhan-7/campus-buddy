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

/** 匹配打分的单元测试：不启动 Spring，直接验证公式。 */
class MatchServiceTest {
  private static final List<Object> SAT_14_16 = List.of(Map.of("day", "周六", "start", "14:00", "end", "16:00"));

  private static Person person(Set<String> interests, Set<String> skills, Set<String> wants) {
    return new Person(interests, skills, wants);
  }

  @Test
  @DisplayName("QR-M1 没有共同空闲时间的候选不推荐")
  void noOverlapNoMatch() {
    List<Object> sunday = List.of(Map.of("day", "周日", "start", "14:00", "end", "16:00"));
    MatchService.Result r = MatchService.score(person(Set.of("羽毛球"), Set.of(), Set.of()), SAT_14_16, Set.of(),
        person(Set.of("羽毛球"), Set.of(), Set.of()), List.of(sunday), Set.of());
    assertTrue(r.score() == null, "无共同时间时不应给出分数");
  }

  @Test
  @DisplayName("QR-M2 满足全部条件时得满分 100，并给出四条推荐理由")
  void fullScore() {
    Person me = person(Set.of("羽毛球"), Set.of("python"), Set.of("吉他"));
    Person them = person(Set.of("羽毛球"), Set.of("吉他"), Set.of());
    MatchService.Result r = MatchService.score(me, SAT_14_16, Set.of("运动"), them, List.of(SAT_14_16), Set.of("运动"));
    assertEquals(100, (int) r.score());
    assertEquals(4, r.reasons().size());
  }

  @Test
  @DisplayName("QR-M3 只有 1 小时共同空闲时得 15 分（30 × 60/120）")
  void partialOverlap() {
    List<Object> sat15to17 = List.of(Map.of("day", "周六", "start", "15:00", "end", "17:00"));
    MatchService.Result r = MatchService.score(person(Set.of(), Set.of(), Set.of()), SAT_14_16, Set.of(),
        person(Set.of(), Set.of(), Set.of()), List.of(sat15to17), Set.of());
    assertEquals(15, (int) r.score());
    assertTrue(r.reasons().contains("共同空闲约 1.0 小时"));
  }

  @Test
  @DisplayName("QR-M4 技能互补是双向的：对方想学我擅长的也算")
  void reverseComplement() {
    Person me = person(Set.of(), Set.of("摄影"), Set.of());
    Person them = person(Set.of(), Set.of(), Set.of("摄影"));
    MatchService.Result r = MatchService.score(me, SAT_14_16, Set.of(), them, List.of(SAT_14_16), Set.of());
    assertEquals(45, (int) r.score());
  }

  @Test
  @DisplayName("QR-R1 历史脏数据（时间格式错误）不导致推荐接口报错")
  void malformedSlotsIgnored() {
    List<Object> broken = List.of(Map.of("day", "周六", "start", "25:00", "end", "x"));
    assertEquals(0, MatchService.overlapMinutes(SAT_14_16, broken));
    assertEquals(0, MatchService.overlapMinutes(SAT_14_16, List.of("不是对象")));
  }
}
