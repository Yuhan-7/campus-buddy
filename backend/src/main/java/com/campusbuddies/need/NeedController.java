package com.campusbuddies.need;

import com.campusbuddies.auth.AccountService;
import com.campusbuddies.auth.SessionService;
import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import com.campusbuddies.common.Inputs;
import com.campusbuddies.common.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.campusbuddies.common.Inputs.require;
import static com.campusbuddies.common.Inputs.str;

/**
 * 需求发布与需求广场。
 * 广场列表、详情和热门主题允许未登录访问（公约第九条：核心功能免注册体验）；发布、邀请等写操作仍需登录。
 */
@RestController
@RequestMapping("/api")
public class NeedController {
  public static final List<String> CATEGORIES = List.of("学习", "运动", "兴趣", "技能互助", "项目组队");
  /** 每页条数：控制低配手机一次渲染的卡片数量（公约第十三条）。 */
  static final int PAGE_SIZE = 24;
  private static final Pattern BUDDY_TITLE = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,12})搭子");
  private static final String PUBLIC_OPEN = " FROM needs n JOIN users u ON u.id=n.user_id LEFT JOIN disabled_users du ON du.user_id=u.id WHERE n.status='open' AND du.user_id IS NULL";

  private final Db db;
  private final Json json;
  private final SessionService sessions;

  public NeedController(Db db, Json json, SessionService sessions) {
    this.db = db;
    this.json = json;
    this.sessions = sessions;
  }

  private Map<String, Object> view(Map<String, Object> need) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (String key : List.of("id", "user_id", "category", "title", "description", "status", "owner_name")) view.put(key, need.get(key));
    view.put("tags", json.decode(need.get("tags")));
    view.put("slots", json.decode(need.get("slots")));
    view.put("views", need.getOrDefault("views", 0));
    view.put("created_at", String.valueOf(need.get("created_at")));
    return view;
  }

  /** 需求广场：分类与关键词在数据库中筛选后再分页，避免“先截断再筛选”漏掉结果。 */
  @GetMapping("/needs")
  Map<String, Object> needs(@RequestParam(defaultValue = "") String category,
                            @RequestParam(name = "q", defaultValue = "") String keyword,
                            @RequestParam(defaultValue = "0") int offset) {
    StringBuilder sql = new StringBuilder("SELECT n.*,u.name owner_name,(SELECT COUNT(*) FROM need_views v WHERE v.need_id=n.id) views").append(PUBLIC_OPEN);
    List<Object> args = new ArrayList<>();
    if (!category.isBlank()) {
      if (!CATEGORIES.contains(category)) throw ApiException.bad("类型不正确");
      sql.append(" AND n.category=?");
      args.add(category);
    }
    String q = keyword.trim().toLowerCase(Locale.ROOT);
    if (q.length() > 40) throw ApiException.bad("关键词最多 40 字");
    if (!q.isEmpty()) {
      sql.append(" AND LOWER(CONCAT(n.title,' ',n.description,' ',n.tags)) LIKE ?");
      args.add(Inputs.likePattern(q));
    }
    sql.append(" ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?");
    args.add(PAGE_SIZE + 1);
    args.add(Math.max(0, offset));
    List<Map<String, Object>> found = db.rows(sql.toString(), args.toArray());
    boolean hasMore = found.size() > PAGE_SIZE;
    List<Map<String, Object>> page = found.stream().limit(PAGE_SIZE).map(this::view).toList();
    return Map.of("needs", page, "has_more", hasMore);
  }

  /** 需求详情：登录用户打开时记一次浏览（同一账号每天每帖只计一次）；访客只读不计数。 */
  @GetMapping("/needs/{needId}")
  Map<String, Object> needDetail(@PathVariable long needId, HttpServletRequest req) {
    Map<String, Object> me = sessions.optionalUser(req);
    Map<String, Object> need = db.must("SELECT n.*,u.name owner_name" + PUBLIC_OPEN + " AND n.id=?", "需求不存在或已关闭", needId);
    if (me != null) {
      try {
        db.update("INSERT INTO need_views(need_id,user_id,view_date) VALUES(?,?,?)", needId, Db.id(me), Date.valueOf(LocalDate.now(AccountService.ZONE)));
      } catch (DuplicateKeyException ignored) {
        // 当天已经记过一次
      }
    }
    Map<String, Object> detail = view(need);
    detail.put("views", db.count("SELECT COUNT(*) FROM need_views WHERE need_id=?", needId));
    return Map.of("need", detail);
  }

  /** 近 7 天热门主题：来自需求标签与标题中的“××搭子”，按 帖子数×3 + 浏览量 排序。 */
  @GetMapping("/trending")
  Map<String, Object> trending() {
    LocalDate since = LocalDate.now(AccountService.ZONE).minusDays(6);
    Map<String, long[]> counts = new HashMap<>();
    List<Map<String, Object>> recent = db.rows("SELECT n.id,n.title,n.tags,(SELECT COUNT(*) FROM need_views v WHERE v.need_id=n.id AND v.view_date>=?) views_7d"
            + " FROM needs n LEFT JOIN disabled_users du ON du.user_id=n.user_id WHERE n.status='open' AND du.user_id IS NULL AND n.created_at>=? ORDER BY n.created_at DESC LIMIT 500",
        Date.valueOf(since), Timestamp.valueOf(since.atStartOfDay()));
    for (Map<String, Object> need : recent) {
      for (String topic : topicsOf(json.decode(need.get("tags")), String.valueOf(need.get("title")))) {
        long[] c = counts.computeIfAbsent(topic, k -> new long[2]);
        c[0]++;
        c[1] += ((Number) need.get("views_7d")).longValue();
      }
    }
    List<Map<String, Object>> result = new ArrayList<>();
    counts.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue()[0] * 3 + b.getValue()[1], a.getValue()[0] * 3 + a.getValue()[1]))
        .limit(8)
        .forEach(e -> result.add(Map.of("topic", e.getKey(), "posts", e.getValue()[0], "views_7d", e.getValue()[1])));
    return Map.of("topics", result);
  }

  static Set<String> topicsOf(List<Object> tags, String title) {
    Set<String> topics = new LinkedHashSet<>();
    for (Object tag : tags) {
      String t = String.valueOf(tag).trim();
      if (t.length() >= 2 && t.length() <= 20) topics.add(t);
    }
    Matcher m = BUDDY_TITLE.matcher(title);
    while (m.find()) {
      String t = m.group(1).replaceFirst("^[找求约组]", "");
      if (t.length() >= 2) topics.add(t);
    }
    return topics;
  }

  @PostMapping("/needs")
  ResponseEntity<Map<String, Object>> createNeed(@RequestBody Map<String, Object> d, HttpServletRequest req) {
    sessions.sameOrigin(req);
    Map<String, Object> user = sessions.requireUser(req);
    String category = str(d, "category");
    String title = require(str(d, "title"), 3, 60, "标题须为 3–60 字");
    String description = require(str(d, "description"), 0, 500, "说明最多 500 字");
    if (!CATEGORIES.contains(category)) throw ApiException.bad("类型不正确");
    List<Map<String, String>> periods = Inputs.slots(d.get("slots"));
    if (periods.isEmpty()) throw ApiException.bad("请选一个期望时间段");
    long id = db.insert("INSERT INTO needs(user_id,category,title,description,tags,slots) VALUES(?,?,?,?,?,?)",
        Db.id(user), category, title, description, json.encode(Inputs.labels(d.get("tags"), 12)), json.encode(periods));
    return ResponseEntity.status(201).body(Map.of("id", id));
  }

  @GetMapping("/my-needs")
  Map<String, Object> myNeeds(HttpServletRequest req) {
    Map<String, Object> user = sessions.requireUser(req);
    List<Map<String, Object>> mine = db.rows("SELECT n.*,u.name owner_name,(SELECT COUNT(*) FROM need_views v WHERE v.need_id=n.id) views"
        + " FROM needs n JOIN users u ON u.id=n.user_id WHERE n.user_id=? AND n.status<>'deleted' ORDER BY n.created_at DESC", Db.id(user));
    return Map.of("needs", mine.stream().map(this::view).toList());
  }

  private Map<String, Object> ownNeed(long needId, Map<String, Object> user) {
    Map<String, Object> need = db.must("SELECT * FROM needs WHERE id=?", "需求不存在", needId);
    if (Db.longValue(need, "user_id") != Db.id(user)) throw ApiException.forbidden();
    return need;
  }

  /** 关闭 / 重新开放：可恢复操作，前端提供“撤销”而不是确认框（公约第五条）。 */
  @PatchMapping("/needs/{needId}")
  Map<String, Object> needStatus(@PathVariable long needId, @RequestBody Map<String, Object> d, HttpServletRequest req) {
    sessions.sameOrigin(req);
    Map<String, Object> need = ownNeed(needId, sessions.requireUser(req));
    if ("removed".equals(need.get("status"))) throw ApiException.conflict("帖子已被管理员撤下");
    if ("deleted".equals(need.get("status"))) throw ApiException.missing("需求不存在");
    String status = str(d, "status");
    if (!Set.of("open", "closed").contains(status)) throw ApiException.bad("状态不正确");
    db.update("UPDATE needs SET status=? WHERE id=?", status, needId);
    return Map.of("ok", true, "status", status);
  }

  /** 删除：不可恢复，前端须二次确认；删除会撤回待处理邀请，已同意的记录保留。 */
  @DeleteMapping("/needs/{needId}")
  Map<String, Object> deleteNeed(@PathVariable long needId, HttpServletRequest req) {
    sessions.sameOrigin(req);
    Map<String, Object> need = ownNeed(needId, sessions.requireUser(req));
    if ("deleted".equals(need.get("status"))) return Map.of("ok", true);
    db.update("UPDATE needs SET status='deleted' WHERE id=?", needId);
    db.update("UPDATE invitations SET status='withdrawn',updated_at=CURRENT_TIMESTAMP(3) WHERE need_id=? AND status='pending'", needId);
    return Map.of("ok", true);
  }
}
