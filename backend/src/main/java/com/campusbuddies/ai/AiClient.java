package com.campusbuddies.ai;

import java.util.List;

/**
 * AI 能力的抽象接口（依赖倒置）：业务代码只依赖这个接口，不依赖具体是哪家大模型。
 * 目前有两个实现：{@link RuleBasedAiClient}（本地规则，无需联网）和 {@link LlmAiClient}（OpenAI 兼容的大模型接口）。
 */
public interface AiClient {
  /** 把一句自然语言需求拆成表单草稿；无法识别的字段留空。 */
  NeedDraft draftNeed(String text);

  /** 对帖子做违规初筛；只给出提示，是否处理由管理员决定。 */
  Moderation moderate(String title, String description);

  /** 表单草稿。source 为 "rules" 或 "llm"，告诉用户是谁生成的。 */
  record NeedDraft(String category, String title, String description, List<String> tags, String day, String start, String end, String source) {}

  /** 初筛结果：flagged 为 true 时 reason 说明疑似原因。 */
  record Moderation(boolean flagged, String reason, String source) {}
}
