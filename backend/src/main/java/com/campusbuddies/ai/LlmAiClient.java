package com.campusbuddies.ai;

import com.campusbuddies.common.Inputs;
import com.campusbuddies.match.TagNormalizer;
import com.campusbuddies.need.NeedController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 大模型实现：调用 OpenAI 兼容的 /chat/completions 接口（多数国内外大模型平台都提供这种格式）。
 * 只发送用户自己输入的那句话或帖子标题、说明，不发送任何个人资料和联系方式。
 * 返回结果会逐项校验（类型、星期、时间、标签都必须在允许范围内），不合格的字段丢弃。
 */
public class LlmAiClient implements AiClient {
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper mapper;
  private final String url;
  private final String key;
  private final String model;

  public LlmAiClient(ObjectMapper mapper, String baseUrl, String key, String model) {
    this.mapper = mapper;
    this.url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
    this.key = key;
    this.model = model;
  }

  private JsonNode ask(String system, String user) throws Exception {
    Map<String, Object> body = Map.of(
        "model", model,
        "temperature", 0.2,
        "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + key)
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) throw new IllegalStateException("大模型接口返回 " + response.statusCode());
    String content = mapper.readTree(response.body()).get("choices").get(0).get("message").get("content").asText();
    int from = content.indexOf('{'), to = content.lastIndexOf('}');
    if (from < 0 || to <= from) throw new IllegalStateException("大模型未返回 JSON");
    return mapper.readTree(content.substring(from, to + 1));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null ? "" : value.asText().trim();
  }

  @Override
  public NeedDraft draftNeed(String input) {
    String system = "你是校园搭子平台的发布助手。把用户的一句话拆成 JSON，只输出 JSON，不要解释。字段：\n"
        + "category：只能是 " + NeedController.CATEGORIES + " 之一；\n"
        + "title：3 到 30 字的标题；description：对原话的简短整理，不超过 100 字；\n"
        + "tags：最多 5 个，尽量从这个列表中选：" + TagNormalizer.CANONICAL + "；\n"
        + "day：只能是 " + Inputs.DAYS + " 之一，“周末”取周六，无法判断则为空字符串；\n"
        + "start、end：HH:mm 格式，24 小时制，无法判断则为空字符串。";
    try {
      JsonNode node = ask(system, input);
      String category = text(node, "category");
      if (!NeedController.CATEGORIES.contains(category)) category = "";
      String title = text(node, "title");
      if (title.length() < 3 || title.length() > 60) title = "";
      String description = text(node, "description");
      if (description.length() > 500) description = description.substring(0, 500);
      String day = text(node, "day");
      if (!Inputs.DAYS.contains(day)) day = "";
      String start = "", end = "";
      try {
        int s = RuleBasedAiClient.clamp(Inputs.minute(text(node, "start")));
        int e = RuleBasedAiClient.clamp(Inputs.minute(text(node, "end")));
        if (e > s) { start = RuleBasedAiClient.fmt(s); end = RuleBasedAiClient.fmt(e); }
      } catch (RuntimeException ignored) {
        // 时间无法识别时留空，由用户自己选
      }
      LinkedHashSet<String> tags = new LinkedHashSet<>();
      JsonNode list = node.get("tags");
      if (list != null) for (int i = 0; i < list.size() && tags.size() < 5; i++) {
        String tag = TagNormalizer.normalize(list.get(i).asText());
        if (!tag.isBlank() && tag.length() <= 30) tags.add(tag);
      }
      return new NeedDraft(category, title, description, new ArrayList<>(tags), day, start, end, "llm");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Moderation moderate(String title, String description) {
    String system = "你是校园平台的内容审核助手。判断帖子是否疑似违规（代写代考、有偿交易、广告引流、诈骗、色情暴力、人身攻击等）。"
        + "只输出 JSON：{\"flagged\": true 或 false, \"reason\": \"不超过 20 字的原因，未违规则为空\"}。";
    try {
      JsonNode node = ask(system, "标题：" + title + "\n说明：" + description);
      JsonNode flagged = node.get("flagged");
      boolean yes = flagged != null && flagged.asBoolean();
      String reason = text(node, "reason");
      return new Moderation(yes, yes ? (reason.isEmpty() ? "大模型提示疑似违规" : reason) : "", "llm");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
