package com.campusbuddies.invitation;

import com.campusbuddies.auth.AccountService;
import com.campusbuddies.auth.SessionService;
import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.campusbuddies.common.Inputs.number;
import static com.campusbuddies.common.Inputs.str;

/**
 * 邀请流程：pending → accepted / rejected（接收方） 或 withdrawn（发送方）。
 * 只有 accepted 的邀请双方才能查看彼此联系方式。
 */
@RestController
@RequestMapping("/api")
public class InvitationController {
  private final Db db;
  private final SessionService sessions;

  public InvitationController(Db db, SessionService sessions) {
    this.db = db;
    this.sessions = sessions;
  }

  @PostMapping("/invitations")
  ResponseEntity<Map<String, Object>> invite(@RequestBody Map<String, Object> d, HttpServletRequest req) {
    sessions.sameOrigin(req);
    Map<String, Object> me = sessions.requireUser(req);
    long myId = Db.id(me);
    long needId = number(d, "need_id"), receiverId = number(d, "receiver_id");
    Map<String, Object> need = db.must("SELECT * FROM needs WHERE id=?", "需求不存在", needId);
    Map<String, Object> receiver = db.must("SELECT * FROM users WHERE id=?", "用户不存在", receiverId);
    if (!"open".equals(need.get("status"))) throw ApiException.conflict("需求已关闭");
    if (sessions.isDeleted(receiverId)) throw ApiException.bad("对方账号已注销");
    if (sessions.isDisabled(receiverId)) throw ApiException.bad("对方账号已停用");
    long owner = Db.longValue(need, "user_id");
    // 只能是：需求发布者邀请他人，或他人向发布者申请
    if (receiverId == myId || (owner != myId && owner != receiverId)) throw ApiException.bad("邀请对象不正确");
    if (!AccountService.hasContact(me)) throw ApiException.bad("请先在“个人资料”中填写至少一种联系方式，对方同意后才能联系你");
    if (!AccountService.hasContact(receiver)) throw ApiException.bad("对方尚未填写联系方式，暂时无法邀请");
    boolean exists = db.exists("SELECT id FROM invitations WHERE need_id=? AND status IN ('pending','accepted') AND ((sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?))",
        needId, myId, receiverId, receiverId, myId);
    if (exists) throw ApiException.conflict("双方已有待处理或已同意的邀请");
    long id = db.insert("INSERT INTO invitations(need_id,sender_id,receiver_id) VALUES(?,?,?)", needId, myId, receiverId);
    return ResponseEntity.status(201).body(Map.of("id", id));
  }

  @GetMapping("/invitations")
  Map<String, Object> invitations(HttpServletRequest req) {
    long myId = Db.id(sessions.requireUser(req));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> i : db.rows("SELECT i.*,n.title need_title,s.name sender_name,r.name receiver_name FROM invitations i JOIN needs n ON n.id=i.need_id"
        + " JOIN users s ON s.id=i.sender_id JOIN users r ON r.id=i.receiver_id WHERE (i.sender_id=? OR i.receiver_id=?) AND (n.status<>'deleted' OR i.status='accepted')"
        + " ORDER BY i.created_at DESC", myId, myId)) {
      Map<String, Object> x = new LinkedHashMap<>();
      for (String k : List.of("id", "need_id", "need_title", "sender_id", "sender_name", "receiver_id", "receiver_name", "status")) x.put(k, i.get(k));
      x.put("created_at", String.valueOf(i.get("created_at")));
      out.add(x);
    }
    return Map.of("invitations", out);
  }

  @PatchMapping("/invitations/{invId}")
  Map<String, Object> invitationStatus(@PathVariable long invId, @RequestBody Map<String, Object> d, HttpServletRequest req) {
    sessions.sameOrigin(req);
    long myId = Db.id(sessions.requireUser(req));
    Map<String, Object> inv = db.must("SELECT * FROM invitations WHERE id=?", "邀请不存在", invId);
    if (!"pending".equals(inv.get("status"))) throw ApiException.conflict("邀请已处理");
    String action = str(d, "action");
    long sender = Db.longValue(inv, "sender_id"), receiver = Db.longValue(inv, "receiver_id");
    boolean receiverAction = Set.of("accepted", "rejected").contains(action) && receiver == myId;
    boolean senderAction = "withdrawn".equals(action) && sender == myId;
    if (!receiverAction && !senderAction) throw ApiException.forbidden();
    // 带 status='pending' 条件更新，防止双方同时操作造成重复处理
    int updated = db.update("UPDATE invitations SET status=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='pending'", action, invId);
    if (updated == 0) throw ApiException.conflict("邀请已处理");
    return Map.of("ok", true);
  }

  @GetMapping("/contacts/{invId}")
  Map<String, Object> contacts(@PathVariable long invId, HttpServletRequest req) {
    long myId = Db.id(sessions.requireUser(req));
    Map<String, Object> inv = db.must("SELECT * FROM invitations WHERE id=?", "邀请不存在", invId);
    long sender = Db.longValue(inv, "sender_id"), receiver = Db.longValue(inv, "receiver_id");
    if (!"accepted".equals(inv.get("status")) || (myId != sender && myId != receiver)) throw ApiException.forbidden();
    List<Map<String, Object>> out = new ArrayList<>();
    for (long uid : List.of(sender, receiver)) {
      Map<String, Object> u = db.must("SELECT * FROM users WHERE id=?", "用户不存在", uid);
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("name", u.get("name"));
      x.put("email", u.get("contact_email"));
      x.put("phone", u.get("phone"));
      x.put("wechat", u.get("wechat"));
      x.put("qq", u.get("qq"));
      out.add(x);
    }
    return Map.of("contacts", out);
  }
}
