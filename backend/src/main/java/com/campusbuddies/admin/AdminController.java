package com.campusbuddies.admin;

import com.campusbuddies.auth.AccountService;
import com.campusbuddies.auth.SessionService;
import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import com.campusbuddies.profile.UserViews;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.campusbuddies.common.Inputs.number;
import static com.campusbuddies.common.Inputs.require;
import static com.campusbuddies.common.Inputs.str;

/** 管理端：管理员只看公开资料，不看联系方式；通过站内信联系发布者。 */
@RestController
@RequestMapping("/api")
public class AdminController {
  private final Db db;
  private final SessionService sessions;
  private final AccountService accounts;
  private final UserViews views;

  public AdminController(Db db, SessionService sessions, AccountService accounts, UserViews views) {
    this.db = db;
    this.sessions = sessions;
    this.accounts = accounts;
    this.views = views;
  }

  @GetMapping("/admin/overview")
  Map<String, Object> overview(HttpServletRequest req) {
    sessions.requireAdmin(req);
    List<Map<String, Object>> users = new ArrayList<>();
    for (Map<String, Object> u : db.rows("SELECT * FROM users ORDER BY id DESC LIMIT 500")) {
      long uid = Db.id(u);
      Map<String, Object> x = views.publicView(u);
      x.put("account_id", accounts.accountId(uid));
      x.put("admin", sessions.isAdmin(uid));
      x.put("disabled", sessions.isDisabled(uid));
      x.put("deleted", sessions.isDeleted(uid));
      x.put("created_at", String.valueOf(u.get("created_at")));
      users.add(x);
    }
    List<Map<String, Object>> needs = db.rows("SELECT n.id,n.user_id,u.name owner_name,n.category,n.title,n.description,n.status,n.created_at FROM needs n"
        + " JOIN users u ON u.id=n.user_id WHERE n.status<>'deleted' ORDER BY n.id DESC LIMIT 500");
    List<Map<String, Object>> invitations = db.rows("SELECT i.id,i.need_id,n.title need_title,i.sender_id,s.name sender_name,i.receiver_id,r.name receiver_name,i.status,i.created_at"
        + " FROM invitations i JOIN needs n ON n.id=i.need_id JOIN users s ON s.id=i.sender_id JOIN users r ON r.id=i.receiver_id WHERE i.status='accepted' ORDER BY i.id DESC LIMIT 500");
    return Map.of("users", users, "needs", needs, "invitations", invitations);
  }

  @PatchMapping("/admin/users/{userId}")
  Map<String, Object> user(@PathVariable long userId, @RequestBody Map<String, Object> data, HttpServletRequest req) {
    sessions.sameOrigin(req);
    sessions.requireAdmin(req);
    if (sessions.isAdmin(userId)) throw ApiException.bad("不能停用管理员");
    db.must("SELECT id FROM users WHERE id=?", "用户不存在", userId);
    if (sessions.isDeleted(userId)) throw ApiException.bad("该账号已注销");
    String action = str(data, "action");
    if ("disable".equals(action)) {
      if (!sessions.isDisabled(userId)) db.update("INSERT INTO disabled_users(user_id) VALUES(?)", userId);
      db.update("DELETE FROM sessions WHERE user_id=?", userId);
    } else if ("enable".equals(action)) {
      db.update("DELETE FROM disabled_users WHERE user_id=?", userId);
    } else {
      throw ApiException.bad("操作不正确");
    }
    return Map.of("ok", true);
  }

  @PatchMapping("/admin/needs/{needId}")
  Map<String, Object> need(@PathVariable long needId, @RequestBody Map<String, Object> data, HttpServletRequest req) {
    sessions.sameOrigin(req);
    sessions.requireAdmin(req);
    db.must("SELECT id FROM needs WHERE id=?", "需求不存在", needId);
    if (!"remove".equals(str(data, "action"))) throw ApiException.bad("操作不正确");
    db.update("UPDATE needs SET status='removed' WHERE id=?", needId);
    return Map.of("ok", true);
  }

  @PostMapping("/admin/messages")
  Map<String, Object> sendMessage(@RequestBody Map<String, Object> data, HttpServletRequest req) {
    sessions.sameOrigin(req);
    Map<String, Object> admin = sessions.requireAdmin(req);
    long needId = number(data, "need_id");
    Map<String, Object> need = db.must("SELECT id,user_id FROM needs WHERE id=?", "需求不存在", needId);
    String body = require(str(data, "body"), 1, 500, "私信须为 1–500 字");
    long receiver = Db.longValue(need, "user_id");
    if (receiver == Db.id(admin)) throw ApiException.bad("不能给自己发送私信");
    db.update("INSERT INTO admin_messages(sender_id,receiver_id,need_id,body) VALUES(?,?,?,?)", Db.id(admin), receiver, needId, body);
    return Map.of("ok", true);
  }

  /** 普通用户查看收到的站内信；管理员查看自己发出的站内信（含接收人）。 */
  @GetMapping("/messages")
  Map<String, Object> messages(HttpServletRequest req) {
    long myId = Db.id(sessions.requireUser(req));
    if (sessions.isAdmin(myId)) {
      return Map.of("messages", db.rows("SELECT m.id,m.need_id,n.title need_title,m.body,m.created_at,r.name receiver_name FROM admin_messages m"
          + " JOIN needs n ON n.id=m.need_id JOIN users r ON r.id=m.receiver_id WHERE m.sender_id=? ORDER BY m.created_at DESC LIMIT 200", myId));
    }
    return Map.of("messages", db.rows("SELECT m.id,m.need_id,n.title need_title,m.body,m.created_at,s.name sender_name FROM admin_messages m"
        + " JOIN needs n ON n.id=m.need_id JOIN users s ON s.id=m.sender_id WHERE m.receiver_id=? ORDER BY m.created_at DESC LIMIT 100", myId));
  }
}
