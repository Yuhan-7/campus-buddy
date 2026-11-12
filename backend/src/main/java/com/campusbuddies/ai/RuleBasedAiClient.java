package com.campusbuddies.ai;

import com.campusbuddies.common.Inputs;
import com.campusbuddies.match.TagNormalizer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地规则实现：不联网、不把任何数据发给第三方。
 * 未配置大模型、或大模型调用失败时都使用它，保证“智能填写”和“违规初筛”始终可用。
 */
public class RuleBasedAiClient implements AiClient {
  private static final Map<String, List<String>> CATEGORY_WORDS = new LinkedHashMap<>();
  private static final List<String> BANNED = List.of("代写", "代考", "替考", "刷单", "有偿", "日结", "转账", "贷款", "赌", "博彩", "代课", "兼职");
  private static final Pattern RANGE = Pattern.compile("(\\d{1,2})\\s*(?:[:：点]\\s*(\\d{2}|半)?)?\\s*(?:到|至|-|~|—)\\s*(\\d{1,2})\\s*(?:[:：点]\\s*(\\d{2}|半)?)?");

  static {
    CATEGORY_WORDS.put("项目组队", List.of("组队", "队友", "项目", "比赛", "大创", "建模"));
    CATEGORY_WORDS.put("技能互助", List.of("教", "互助", "答疑", "请教", "交换"));
    CATEGORY_WORDS.put("学习", List.of("自习", "复习", "考研", "高数", "英语", "口语", "背单词", "刷题", "图书馆", "期末"));
    CATEGORY_WORDS.put("运动", List.of("羽毛球", "篮球", "足球", "跑", "健身", "游泳", "骑行", "乒乓", "网球", "打球"));
    CATEGORY_WORDS.put("兴趣", List.of("摄影", "拍照", "看展", "桌游", "狼人杀", "剧本杀", "电影", "咖啡", "读书", "吉他", "探店"));
  }

  @Override
  public NeedDraft draftNeed(String text) {
    String t = text == null ? "" : text.trim();
    String category = "";
    for (var e : CATEGORY_WORDS.entrySet()) {
      if (e.getValue().stream().anyMatch(t::contains)) { category = e.getKey(); break; }
    }
    List<String> tags = TagNormalizer.findIn(t);
    if (tags.size() > 5) tags = tags.subList(0, 5);
    String day = day(t);
    String[] time = time(t);
    String period = t.contains("早上") || t.contains("清晨") ? "清晨" : t.contains("上午") ? "上午" : t.contains("中午") ? "中午"
        : t.contains("下午") ? "下午" : t.contains("晚上") || t.contains("夜") ? "晚上" : "";
    String subject = tags.isEmpty() ? "" : tags.get(0);
    String title = (subject.isEmpty() ? (t.length() > 20 ? t.substring(0, 20) : t) : day + period + subject + "搭子").trim();
    if (title.length() < 3) title = "";
    return new NeedDraft(category, title, t.length() > 500 ? t.substring(0, 500) : t, tags, day, time[0], time[1], "rules");
  }

  static String day(String t) {
    for (String d : Inputs.DAYS) {
      String n = d.substring(1);
      if (t.contains(d) || t.contains("星期" + n) || t.contains("礼拜" + n)) return d;
    }
    if (t.contains("周日") || t.contains("星期天") || t.contains("礼拜天")) return "周日";
    if (t.contains("周末")) return "周六";
    return "";
  }

  /** 解析时间段：优先识别“3点到5点”“14:00-16:00”，否则按上午/下午/晚上给出常用时段。返回 [开始, 结束]，对齐到半小时。 */
  static String[] time(String t) {
    boolean pm = t.contains("下午") || t.contains("晚上") || t.contains("傍晚") || t.contains("夜");
    Matcher m = RANGE.matcher(t);
    if (m.find()) {
      int sh = Integer.parseInt(m.group(1)), eh = Integer.parseInt(m.group(3));
      int sm = minutes(m.group(2)), em = minutes(m.group(4));
      if (pm && sh < 12) sh += 12;
      if (pm && eh < 12) eh += 12;
      int start = clamp(sh * 60 + sm), end = clamp(eh * 60 + em);
      if (end > start) return new String[] {fmt(start), fmt(end)};
    }
    if (t.contains("早上") || t.contains("清晨") || t.contains("晨")) return new String[] {"07:00", "09:00"};
    if (t.contains("上午")) return new String[] {"09:00", "11:00"};
    if (t.contains("中午")) return new String[] {"12:00", "13:30"};
    if (t.contains("下午")) return new String[] {"14:00", "16:00"};
    if (t.contains("晚上") || t.contains("夜")) return new String[] {"19:00", "21:00"};
    return new String[] {"", ""};
  }

  private static int minutes(String s) {
    if (s == null) return 0;
    if (s.equals("半")) return 30;
    return Integer.parseInt(s);
  }

  /** 对齐到半小时，并限制在 06:00–23:00（与前端时间选项一致）。 */
  static int clamp(int minute) {
    int rounded = Math.round(minute / 30f) * 30;
    return Math.max(6 * 60, Math.min(23 * 60, rounded));
  }

  static String fmt(int minute) {
    return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
  }

  @Override
  public Moderation moderate(String title, String description) {
    String text = (title == null ? "" : title) + " " + (description == null ? "" : description);
    List<String> hits = BANNED.stream().filter(text::contains).toList();
    return hits.isEmpty() ? new Moderation(false, "", "rules") : new Moderation(true, "含敏感词：" + String.join("、", hits), "rules");
  }
}
