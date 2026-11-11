package com.campusbuddies;

import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Inputs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输入校验的单元测试。 */
class InputsTest {
  private static boolean rejects(Runnable action) {
    try {
      action.run();
      return false;
    } catch (ApiException e) {
      return true;
    }
  }

  @Test
  @DisplayName("QR-R2 邮箱格式校验")
  void emails() {
    assertTrue(Inputs.validEmail("a.b@school.edu.cn"));
    assertFalse(Inputs.validEmail("bad..mail@test.example"));
    assertFalse(Inputs.validEmail("no-at-sign"));
    assertFalse(Inputs.validEmail("x@localhost"));
  }

  @Test
  @DisplayName("QR-R3 时间段校验：结束早于开始、跨度超过 12 小时、非法星期均被拒绝")
  void slots() {
    assertTrue(rejects(() -> Inputs.slots(List.of(Map.of("day", "周六", "start", "16:00", "end", "14:00")))));
    assertTrue(rejects(() -> Inputs.slots(List.of(Map.of("day", "周六", "start", "06:00", "end", "19:00")))));
    assertTrue(rejects(() -> Inputs.slots(List.of(Map.of("day", "星期八", "start", "14:00", "end", "16:00")))));
    // 重复时间段去重
    Map<String, String> slot = Map.of("day", "周六", "start", "14:00", "end", "16:00");
    assertEquals(1, Inputs.slots(List.of(slot, slot)).size());
  }

  @Test
  @DisplayName("QR-R4 标签去空去重，超长标签被拒绝")
  void labels() {
    assertEquals(List.of("羽毛球"), Inputs.labels(List.of(" 羽毛球 ", "羽毛球", ""), 12));
    assertTrue(rejects(() -> Inputs.labels(List.of("一".repeat(31)), 12)));
  }

  @Test
  @DisplayName("QR-R5 搜索关键词中的 % 和 _ 按字面匹配")
  void likeEscaping() {
    assertEquals("%50\\%\\_off%", Inputs.likePattern("50%_off"));
  }
}
