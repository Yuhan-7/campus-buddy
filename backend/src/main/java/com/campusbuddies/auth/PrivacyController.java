package com.campusbuddies.auth;

import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.campusbuddies.common.Inputs.str;

/**
 * 个人信息使用说明与账号注销。
 * 对应班级公约第三条（数据采集须说明用途）、第十三条（隐私敏感用户）。
 */
@RestController
@RequestMapping("/api")
public class PrivacyController {
  public static final String POLICY_VERSION = "2026-11";
  /** 注销确认词：防止误触，属于公约第五条的不可逆操作二次确认。 */
  public static final String DELETE_CONFIRM_WORD = "确认注销";

  /** 每类数据：采集了什么、用来做什么、谁能看到。前端直接展示这份清单，保证说明与代码一致。 */
  private static final List<Map<String, String>> ITEMS = List.of(
      Map.of("data", "注册邮箱、密码", "purpose", "登录与找回账号；密码仅保存不可逆的哈希值", "visible", "仅本人"),
      Map.of("data", "昵称、年级、兴趣、技能、个人介绍等资料（均可不填）", "purpose", "在需求广场展示，并用于计算匹配推荐分数", "visible", "所有登录用户、管理员"),
      Map.of("data", "联系方式（邮箱、电话、微信、QQ）", "purpose", "邀请被对方同意后交换联系方式；发出邀请前需至少填写一项", "visible", "仅本人与已同意邀请的对方，管理员也不可见"),
      Map.of("data", "发布的需求与期望时间段", "purpose", "在需求广场展示并用于匹配共同空闲时间", "visible", "所有访客（含未登录）"),
      Map.of("data", "帖子浏览记录（账号 + 日期）", "purpose", "统计浏览量与近 7 天热门主题，不用于其他用途", "visible", "仅以汇总数字展示"),
      Map.of("data", "密保问题与答案", "purpose", "忘记密码时验证身份；答案仅保存哈希值", "visible", "问题对输入账号者可见，答案任何人不可见"));

  private final Db db;
  private final SessionService sessions;
  private final AccountService accounts;

  public PrivacyController(Db db, SessionService sessions, AccountService accounts) {
    this.db = db;
    this.sessions = sessions;
    this.accounts = accounts;
  }

  @GetMapping("/privacy")
  Map<String, Object> privacy() {
    return Map.of("version", POLICY_VERSION, "items", ITEMS,
        "notes", List.of("本系统不收费，不向第三方提供任何数据。", "可随时在“个人资料”页注销账号，注销后个人资料与联系方式会被清除。"));
  }

  /**
   * 注销账号：清除个人资料、联系方式、密保与浏览记录，下架本人需求并撤回待处理邀请。
   * 已同意的匹配记录保留（对方的历史记录），但其中本人显示为“已注销用户”，联系方式为空。
   */
  @Transactional
  @DeleteMapping("/me")
  Map<String, Object> deleteAccount(@RequestBody Map<String, Object> data, HttpServletRequest req, HttpServletResponse res) {
    sessions.sameOrigin(req);
    Map<String, Object> user = sessions.requireUser(req);
    long userId = Db.id(user);
    if (sessions.isAdmin(userId)) throw ApiException.bad("管理员账号不能在网页中注销");
    if (!accounts.matches(str(data, "password"), user.get("password_hash"))) throw ApiException.bad("当前密码不正确");
    if (!DELETE_CONFIRM_WORD.equals(str(data, "confirm"))) throw ApiException.bad("请输入“" + DELETE_CONFIRM_WORD + "”以确认");

    String placeholderName = "已注销用户#" + userId;
    byte[] bytes = new byte[24];
    accounts.random().nextBytes(bytes);
    db.update("UPDATE users SET username=?,password_hash=?,name=?,age='',gender='',grade='',hometown='',bio='',"
            + "personality='[]',interests='[]',skills='[]',wants='[]',contact_email='',phone='',wechat='',qq='' WHERE id=?",
        "deleted-" + userId + "@deleted.invalid", accounts.encode(java.util.HexFormat.of().formatHex(bytes)), placeholderName, userId);
    db.update("UPDATE unique_nicknames SET nickname=? WHERE user_id=?", placeholderName, userId);
    db.update("UPDATE needs SET status='deleted' WHERE user_id=? AND status<>'deleted'", userId);
    db.update("UPDATE invitations SET status='withdrawn',updated_at=CURRENT_TIMESTAMP(3) WHERE status='pending' AND (sender_id=? OR receiver_id=?)", userId, userId);
    for (String table : List.of("security_questions", "security_attempts", "login_attempts", "need_views", "privacy_consents", "sessions")) {
      db.update("DELETE FROM " + table + " WHERE user_id=?", userId);
    }
    db.update("INSERT INTO deleted_users(user_id) VALUES(?)", userId);
    sessions.end(req, res);
    return Map.of("ok", true);
  }
}
