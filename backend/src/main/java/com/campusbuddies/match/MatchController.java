package com.campusbuddies.match;

import com.campusbuddies.auth.SessionService;
import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import com.campusbuddies.common.Json;
import com.campusbuddies.profile.UserViews;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 为自己的某条开放需求推荐搭子候选。 */
@RestController
@RequestMapping("/api")
public class MatchController {
  private final Db db;
  private final Json json;
  private final SessionService sessions;
  private final UserViews views;

  public MatchController(Db db, Json json, SessionService sessions, UserViews views) {
    this.db = db;
    this.json = json;
    this.sessions = sessions;
    this.views = views;
  }

  private MatchService.Person person(Map<String, Object> user) {
    return new MatchService.Person(
        MatchService.lower(json.decode(user.get("interests"))),
        MatchService.lower(json.decode(user.get("skills"))),
        MatchService.lower(json.decode(user.get("wants"))));
  }

  @GetMapping("/recommend/{needId}")
  Map<String, Object> recommend(@PathVariable long needId, HttpServletRequest req) {
    Map<String, Object> me = sessions.requireUser(req);
    Map<String, Object> need = db.must("SELECT * FROM needs WHERE id=?", "需求不存在", needId);
    if (Db.longValue(need, "user_id") != Db.id(me)) throw ApiException.forbidden();
    if (!"open".equals(need.get("status"))) throw ApiException.conflict("需求已关闭，重新开放后才能匹配");

    // 一次查出所有候选人的开放需求，按用户分组（替代原来“每个候选人查一次”的 N+1 查询）
    Map<Long, List<List<?>>> slotsByUser = new HashMap<>();
    Map<Long, Set<String>> tagsByUser = new HashMap<>();
    for (Map<String, Object> n : db.rows("SELECT user_id,slots,tags FROM needs WHERE status='open' AND user_id<>?", Db.id(me))) {
      long uid = Db.longValue(n, "user_id");
      slotsByUser.computeIfAbsent(uid, k -> new ArrayList<>()).add(json.decode(n.get("slots")));
      tagsByUser.computeIfAbsent(uid, k -> new HashSet<>()).addAll(MatchService.lower(json.decode(n.get("tags"))));
    }

    MatchService.Person mine = person(me);
    List<Object> needSlots = json.decode(need.get("slots"));
    Set<String> needTags = MatchService.lower(json.decode(need.get("tags")));
    List<Map<String, Object>> result = new ArrayList<>();
    List<Map<String, Object>> candidates = db.rows("SELECT u.* FROM users u LEFT JOIN admins a ON a.user_id=u.id LEFT JOIN disabled_users d ON d.user_id=u.id"
        + " LEFT JOIN deleted_users x ON x.user_id=u.id WHERE u.id<>? AND a.user_id IS NULL AND d.user_id IS NULL AND x.user_id IS NULL", Db.id(me));
    for (Map<String, Object> candidate : candidates) {
      long uid = Db.id(candidate);
      if (!slotsByUser.containsKey(uid)) continue;
      MatchService.Result r = MatchService.score(mine, needSlots, needTags, person(candidate), slotsByUser.get(uid), tagsByUser.getOrDefault(uid, Set.of()));
      if (r.score() == null) continue;
      result.add(Map.of("user", views.publicView(candidate), "score", r.score(), "reasons", r.reasons()));
    }
    result.sort(Comparator.comparingInt(x -> -((Number) x.get("score")).intValue()));
    return Map.of("matches", result.stream().limit(MatchService.MAX_RESULTS).toList());
  }
}
