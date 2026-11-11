package com.campusbuddies.match;

import com.campusbuddies.common.Inputs;

import java.util.*;

/**
 * 规则式匹配打分（纯函数，不依赖数据库，便于单元测试）。
 *
 * <pre>
 * 总分(0–100) = 35 × 兴趣 Jaccard 相似度
 *             + 30 × min(1, 共同空闲分钟 / 120)
 *             + 20 × 需求标签被对方需求覆盖的比例
 *             + 15 × [任一方“想学”与另一方“擅长”有交集]
 * </pre>
 * 没有共同空闲时间的候选直接排除。
 */
public final class MatchService {
  public static final int MAX_RESULTS = 20;

  private MatchService() {}

  /** 参与匹配的一方：兴趣、技能、想学的技能。 */
  public record Person(Set<String> interests, Set<String> skills, Set<String> wants) {}

  /** 打分结果；score 为 null 表示不推荐（无共同空闲时间）。 */
  public record Result(Integer score, List<String> reasons) {}

  public static Set<String> lower(List<?> values) {
    Set<String> result = new LinkedHashSet<>();
    for (Object v : values) if (v instanceof String s && !s.isBlank()) result.add(s.trim().toLowerCase(Locale.ROOT));
    return result;
  }

  /** 两组时间段在同一天的重叠分钟数之和；格式错误的时间段忽略。 */
  public static int overlapMinutes(List<?> mine, List<?> theirs) {
    int total = 0;
    for (Object a : mine) {
      for (Object b : theirs) {
        if (!(a instanceof Map<?, ?> p) || !(b instanceof Map<?, ?> q) || !Objects.equals(p.get("day"), q.get("day"))) continue;
        try {
          int start = Math.max(Inputs.minute(String.valueOf(p.get("start"))), Inputs.minute(String.valueOf(q.get("start"))));
          int end = Math.min(Inputs.minute(String.valueOf(p.get("end"))), Inputs.minute(String.valueOf(q.get("end"))));
          total += Math.max(0, end - start);
        } catch (RuntimeException ignored) {
          // 历史脏数据：跳过该时间段
        }
      }
    }
    return total;
  }

  /**
   * @param me          发起匹配的用户
   * @param needSlots   当前需求的期望时间段
   * @param needTags    当前需求的标签（已小写）
   * @param candidate   候选用户
   * @param theirNeeds  候选用户所有开放需求的时间段列表
   * @param theirTags   候选用户所有开放需求的标签并集（已小写）
   */
  public static Result score(Person me, List<?> needSlots, Set<String> needTags, Person candidate, List<List<?>> theirNeeds, Set<String> theirTags) {
    int common = 0;
    for (List<?> slots : theirNeeds) common = Math.max(common, overlapMinutes(needSlots, slots));
    if (common == 0) return new Result(null, List.of());

    Set<String> shared = new TreeSet<>(me.interests());
    shared.retainAll(candidate.interests());
    Set<String> union = new HashSet<>(me.interests());
    union.addAll(candidate.interests());
    Set<String> topics = new TreeSet<>(needTags);
    topics.retainAll(theirTags);
    Set<String> complement = new TreeSet<>(me.wants());
    complement.retainAll(candidate.skills());
    Set<String> reverse = new HashSet<>(candidate.wants());
    reverse.retainAll(me.skills());
    complement.addAll(reverse);

    double value = 35.0 * shared.size() / Math.max(1, union.size())
        + 30.0 * Math.min(1.0, common / 120.0)
        + 20.0 * topics.size() / Math.max(1, needTags.size())
        + (complement.isEmpty() ? 0 : 15);
    List<String> reasons = new ArrayList<>();
    if (!shared.isEmpty()) reasons.add("共同兴趣：" + String.join("、", shared));
    reasons.add("共同空闲约 " + Math.round(common / 6.0) / 10.0 + " 小时");
    if (!topics.isEmpty()) reasons.add("主题契合：" + String.join("、", topics));
    if (!complement.isEmpty()) reasons.add("技能互补：" + String.join("、", complement));
    return new Result((int) Math.round(value), reasons);
  }
}
