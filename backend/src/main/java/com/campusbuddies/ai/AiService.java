package com.campusbuddies.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 选择 AI 实现并负责降级：
 * 未在 .env 配置 AI_API_KEY 时只用本地规则；配置后优先调用大模型，失败（超时、返回格式不对等）自动退回本地规则。
 * 违规初筛同时参考本地敏感词：任一方判定疑似违规即提示管理员。
 */
@Component
public class AiService implements AiClient {
  private static final Logger log = LoggerFactory.getLogger(AiService.class);
  private final AiClient rules = new RuleBasedAiClient();
  private final AiClient llm;

  public AiService(ObjectMapper mapper,
                   @Value("${AI_API_URL:}") String url,
                   @Value("${AI_API_KEY:}") String key,
                   @Value("${AI_MODEL:}") String model) {
    this.llm = url.isBlank() || key.isBlank() || model.isBlank() ? null : new LlmAiClient(mapper, url, key, model);
  }

  public boolean llmEnabled() {
    return llm != null;
  }

  @Override
  public NeedDraft draftNeed(String text) {
    if (llm != null) {
      try {
        return llm.draftNeed(text);
      } catch (RuntimeException e) {
        log.warn("大模型智能填写失败，改用本地规则：{}", e.getMessage());
      }
    }
    return rules.draftNeed(text);
  }

  @Override
  public Moderation moderate(String title, String description) {
    Moderation local = rules.moderate(title, description);
    if (local.flagged() || llm == null) return local;
    try {
      return llm.moderate(title, description);
    } catch (RuntimeException e) {
      log.warn("大模型违规初筛失败，仅使用本地敏感词：{}", e.getMessage());
      return local;
    }
  }
}
