package com.campusbuddies.match;

import java.util.*;

/**
 * 标签同义词归一：把“打羽毛球”“羽球”“badminton”统一为“羽毛球”，避免因描述不同而匹配不上。
 * 纯函数、本地词表，结果可解释、可测试；AI 发布助手也只从 {@link #CANONICAL} 中选标签。
 */
public final class TagNormalizer {
  /** 规范标签 → 常见别名（别名统一小写比较） */
  private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

  static {
    put("羽毛球", "打羽毛球", "羽球", "badminton", "打球羽毛球");
    put("篮球", "打篮球", "basketball", "投篮");
    put("足球", "踢足球", "踢球", "football", "soccer");
    put("乒乓球", "打乒乓球", "乒乓", "ping pong", "table tennis");
    put("网球", "打网球", "tennis");
    put("跑步", "慢跑", "夜跑", "晨跑", "jogging", "running", "跑操");
    put("健身", "撸铁", "健身房", "gym", "力量训练");
    put("游泳", "swimming", "泳池");
    put("骑行", "骑车", "单车", "cycling");
    put("摄影", "拍照", "扫街", "photography", "拍摄");
    put("看展", "展览", "美术馆", "博物馆");
    put("桌游", "狼人杀", "board game", "三国杀");
    put("剧本杀", "剧本", "密室");
    put("电影", "看电影", "观影", "movie");
    put("阅读", "看书", "读书", "读书会", "reading");
    put("自习", "图书馆", "上自习", "study");
    put("考研", "研究生考试", "备考研究生");
    put("英语口语", "口语", "英语对话", "speaking", "雅思口语", "托福口语");
    put("英语", "english", "四六级", "背单词");
    put("高数", "高等数学", "微积分");
    put("吉他", "弹吉他", "guitar");
    put("Python", "python", "py", "python编程");
    put("Java", "java", "java编程");
    put("Vue", "vue", "vue.js");
    put("编程", "写代码", "coding", "programming", "代码");
    put("修图", "ps", "photoshop", "调色", "lightroom");
    put("视频剪辑", "剪辑", "剪视频", "pr", "premiere");
    put("PPT设计", "ppt", "做ppt", "幻灯片");
    put("咖啡", "探店", "coffee");
    put("数学建模", "建模", "数模");
  }

  public static final List<String> CANONICAL = List.copyOf(ALIASES.keySet());
  private static final Map<String, String> LOOKUP = new HashMap<>();

  static {
    for (var e : ALIASES.entrySet()) {
      LOOKUP.put(e.getKey().toLowerCase(Locale.ROOT), e.getKey());
      for (String alias : e.getValue()) LOOKUP.put(alias.toLowerCase(Locale.ROOT), e.getKey());
    }
  }

  private TagNormalizer() {}

  private static void put(String canonical, String... aliases) {
    ALIASES.put(canonical, List.of(aliases));
  }

  /** 归一单个标签：词表中有则返回规范名，否则原样返回（去空格）。 */
  public static String normalize(String tag) {
    if (tag == null) return "";
    String trimmed = tag.trim();
    String compact = trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    return LOOKUP.getOrDefault(compact, trimmed);
  }

  /** 在一段自由文本中找出出现过的规范标签（按词表顺序，长别名优先避免“口语”抢先于“英语口语”）。 */
  public static List<String> findIn(String text) {
    String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
    List<Map.Entry<String, String>> entries = new ArrayList<>(LOOKUP.entrySet());
    entries.sort((a, b) -> b.getKey().length() - a.getKey().length());
    LinkedHashSet<String> found = new LinkedHashSet<>();
    for (var e : entries) {
      String key = e.getKey();
      // 过短的英文别名（如 py、pr、ps）只在独立出现时匹配，避免误判
      if (key.matches("[a-z]{1,2}") && !lower.matches("(?s).*(^|[^a-z])" + key + "([^a-z]|$).*")) continue;
      if (lower.contains(key)) found.add(e.getValue());
    }
    return new ArrayList<>(found);
  }
}
