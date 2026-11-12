package com.campusbuddies.ai;

import com.campusbuddies.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.campusbuddies.common.Inputs.require;
import static com.campusbuddies.common.Inputs.str;

/** 发布需求助手：把一句话变成表单草稿，用户检查修改后再发布（AI 不直接发布任何内容）。 */
@RestController
@RequestMapping("/api/ai")
public class AiController {
  private final AiService ai;
  private final SessionService sessions;

  public AiController(AiService ai, SessionService sessions) {
    this.ai = ai;
    this.sessions = sessions;
  }

  @PostMapping("/need-draft")
  Map<String, Object> needDraft(@RequestBody Map<String, Object> data, HttpServletRequest req) {
    sessions.sameOrigin(req);
    sessions.requireUser(req);
    String text = require(str(data, "text"), 2, 200, "请用 2–200 字描述你的需求");
    AiClient.NeedDraft d = ai.draftNeed(text);
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("category", d.category());
    draft.put("title", d.title());
    draft.put("description", d.description());
    draft.put("tags", d.tags());
    draft.put("day", d.day());
    draft.put("start", d.start());
    draft.put("end", d.end());
    draft.put("source", d.source());
    return Map.of("draft", draft);
  }
}
