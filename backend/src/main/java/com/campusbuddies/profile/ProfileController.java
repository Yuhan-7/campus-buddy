package com.campusbuddies.profile;

import com.campusbuddies.auth.AccountService;
import com.campusbuddies.auth.SessionService;
import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import com.campusbuddies.common.Json;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.campusbuddies.common.Inputs.labels;
import static com.campusbuddies.common.Inputs.require;
import static com.campusbuddies.common.Inputs.str;

/** 个人资料维护与公开资料查看。 */
@RestController
@RequestMapping("/api")
public class ProfileController {
  static final Set<String> GRADES = Set.of("", "大一", "大二", "大三", "大四", "大五", "研一", "研二", "研三", "博士", "其他");
  static final Set<String> GENDERS = Set.of("", "男", "女", "其他", "不愿透露");
  private final Db db;
  private final Json json;
  private final SessionService sessions;
  private final UserViews views;

  public ProfileController(Db db, Json json, SessionService sessions, UserViews views) {
    this.db = db;
    this.json = json;
    this.sessions = sessions;
    this.views = views;
  }

  @GetMapping("/users/{id}")
  Map<String, Object> user(@PathVariable long id, HttpServletRequest req) {
    sessions.requireUser(req);
    return Map.of("user", views.publicView(db.must("SELECT * FROM users WHERE id=?", "用户不存在", id)));
  }

  @Transactional
  @PatchMapping("/me")
  Map<String, Object> updateMe(@RequestBody Map<String, Object> d, HttpServletRequest req) {
    sessions.sameOrigin(req);
    Map<String, Object> user = sessions.requireUser(req);
    long userId = Db.id(user);
    String name = require(str(d, "name"), 1, 30, "昵称长度须为 1–30 字");
    String age = str(d, "age"), grade = str(d, "grade"), gender = str(d, "gender");
    String hometown = require(str(d, "hometown"), 0, 40, "家乡最多 40 字");
    String bio = require(str(d, "bio"), 0, 300, "介绍最多 300 字");
    String email = str(d, "contact_email").toLowerCase(Locale.ROOT);
    String phone = str(d, "phone");
    String wechat = require(str(d, "wechat"), 0, 50, "微信号过长");
    String qq = str(d, "qq");

    if (!age.isEmpty() && (!age.matches("\\d{1,3}") || Integer.parseInt(age) < 16 || Integer.parseInt(age) > 100)) throw ApiException.bad("年龄须为 16–100 岁");
    if (!GRADES.contains(grade)) throw ApiException.bad("年级不正确");
    if (!GENDERS.contains(gender)) throw ApiException.bad("性别选项不正确");
    if (!email.isEmpty() && (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) throw ApiException.bad("联系邮箱格式不正确");
    if (!phone.isEmpty() && (phone.length() > 25 || !phone.matches("[+0-9 ()-]+"))) throw ApiException.bad("电话格式不正确");
    if (!qq.isEmpty() && !qq.matches("[0-9]{5,15}")) throw ApiException.bad("QQ 号须为 5–15 位数字");
    if (name.startsWith("已注销用户")) throw ApiException.bad("该昵称为系统保留");

    if (!name.equals(user.get("name"))) {
      Map<String, Object> taken = db.row("SELECT user_id FROM unique_nicknames WHERE nickname=?", name);
      if (taken != null && Db.longValue(taken, "user_id") != userId) throw ApiException.conflict("昵称已被使用");
      try {
        db.update("UPDATE unique_nicknames SET nickname=? WHERE user_id=?", name, userId);
      } catch (DuplicateKeyException e) {
        throw ApiException.conflict("昵称已被使用");
      }
    }
    db.update("UPDATE users SET name=?,age=?,gender=?,grade=?,hometown=?,bio=?,personality=?,interests=?,skills=?,wants=?,contact_email=?,phone=?,wechat=?,qq=? WHERE id=?",
        name, age, gender, grade, hometown, bio,
        json.encode(labels(d.get("personality"), 8)), json.encode(labels(d.get("interests"), 12)),
        json.encode(labels(d.get("skills"), 12)), json.encode(labels(d.get("wants"), 12)),
        email, phone, wechat, qq, userId);
    return Map.of("ok", true, "has_contact", AccountService.hasContact(Map.of("contact_email", email, "phone", phone, "wechat", wechat, "qq", qq)));
  }
}
