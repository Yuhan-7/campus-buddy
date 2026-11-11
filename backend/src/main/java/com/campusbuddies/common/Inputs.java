package com.campusbuddies.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 请求参数读取与校验。所有方法均为纯函数，便于单元测试。 */
public final class Inputs {
  public static final List<String> DAYS = List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");
  private static final Pattern TIME = Pattern.compile("(?:[01]\\d|2[0-3]):[0-5]\\d");

  private Inputs() {}

  public static String str(Map<String, Object> body, String key) {
    Object value = body.get(key);
    return value == null ? "" : String.valueOf(value).trim();
  }

  public static long number(Map<String, Object> body, String key) {
    try {
      return Long.parseLong(str(body, key));
    } catch (NumberFormatException e) {
      throw ApiException.bad("编号不正确");
    }
  }

  /** 校验字符串长度在 [min, max] 内，否则返回 400 和给定提示。 */
  public static String require(String value, int min, int max, String message) {
    if (value.length() < min || value.length() > max) throw ApiException.bad(message);
    return value;
  }

  public static boolean validEmail(String email) {
    if (email.length() > 254 || email.contains("..")) return false;
    String[] parts = email.split("@", -1);
    if (parts.length != 2) return false;
    String local = parts[0];
    if (local.isEmpty() || local.length() > 64 || local.startsWith(".") || local.endsWith(".")) return false;
    if (!local.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+")) return false;
    String[] labels = parts[1].split("\\.", -1);
    if (labels.length < 2 || !labels[labels.length - 1].matches("[A-Za-z]{2,}")) return false;
    for (String label : labels) {
      if (label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) return false;
    }
    return true;
  }

  /** 标签列表：去空、去重，单个标签最多 30 字。 */
  public static List<String> labels(Object value, int limit) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list) || list.size() > limit) throw ApiException.bad("标签格式不正确，最多 " + limit + " 个");
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (Object item : list) {
      if (!(item instanceof String text) || text.trim().length() > 30) throw ApiException.bad("单个标签最多 30 字");
      if (!text.trim().isEmpty()) result.add(text.trim());
    }
    return new ArrayList<>(result);
  }

  public static int minute(String time) {
    if (time == null || !TIME.matcher(time).matches()) throw ApiException.bad("时间格式不正确");
    return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3));
  }

  /** 期望时间段：最多 7 段，每段须在同一天内且不超过 12 小时。 */
  public static List<Map<String, String>> slots(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 7) throw ApiException.bad("最多填写 7 个时间段");
    List<Map<String, String>> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> slot)) throw ApiException.bad("时间格式不正确");
      String day = String.valueOf(slot.get("day"));
      String start = String.valueOf(slot.get("start"));
      String end = String.valueOf(slot.get("end"));
      int from = minute(start), to = minute(end);
      if (!DAYS.contains(day) || to <= from || to - from > 720) throw ApiException.bad("时间段不正确：结束时间须晚于开始时间，且不超过 12 小时");
      Map<String, String> normalized = Map.of("day", day, "start", start, "end", end);
      if (!result.contains(normalized)) result.add(normalized);
    }
    return result;
  }

  /** LIKE 查询的通配符转义（MySQL 与 H2 默认转义符均为反斜杠）。 */
  public static String likePattern(String keyword) {
    String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
