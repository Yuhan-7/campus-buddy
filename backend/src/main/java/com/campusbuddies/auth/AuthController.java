package com.campusbuddies.auth;

import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import com.campusbuddies.common.Inputs;
import com.campusbuddies.need.NeedController;
import com.campusbuddies.profile.UserViews;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.campusbuddies.common.Inputs.require;
import static com.campusbuddies.common.Inputs.str;

/** 注册、登录、退出、修改密码、密保找回。 */
@RestController
@RequestMapping("/api")
public class AuthController {
  /** 连续失败 5 次锁定 15 分钟（登录与密保找回共用此策略）。 */
  static final int MAX_FAILURES = 5;
  static final int LOCK_MINUTES = 15;
  private final Db db;
  private final SessionService sessions;
  private final AccountService accounts;
  private final UserViews views;

  public AuthController(Db db, SessionService sessions, AccountService accounts, UserViews views) {
    this.db = db;
    this.sessions = sessions;
    this.accounts = accounts;
    this.views = views;
  }

  @GetMapping("/health")
  Map<String, Object> health() {
    return Map.of("status", "ok", "version", "1.2.0");
  }

  @Transactional
  @PostMapping("/register")
  Map<String, Object> register(@RequestBody Map<String, Object> data, HttpServletRequest req) {
    sessions.sameOrigin(req);
    String email = str(data, "email").toLowerCase(Locale.ROOT);
    String password = str(data, "password");
    if (!Inputs.validEmail(email) || password.length() < 8 || password.length() > 100) throw ApiException.bad("请填写有效邮箱和 8–100 位密码");
    // 公约第三条：采集数据前须说明用途，并取得用户同意
    if (!Boolean.TRUE.equals(data.get("agree_privacy"))) throw ApiException.bad("请先阅读并同意《个人信息使用说明》");
    String nickname = accounts.newNickname();
    long userId = accounts.create(email, password, nickname);
    db.update("INSERT INTO privacy_consents(user_id,policy_version) VALUES(?,?)", userId, PrivacyController.POLICY_VERSION);
    return Map.of("ok", true, "name", nickname, "account_id", accounts.accountId(userId));
  }

  @PostMapping("/login")
  Map<String, Object> login(@RequestBody Map<String, Object> data, HttpServletRequest req, HttpServletResponse res) {
    sessions.sameOrigin(req);
    Map<String, Object> user = accounts.find(str(data, "account"));
    if (user == null) throw ApiException.unauthorized("账号或密码错误");
    long userId = Db.id(user);
    ensureNotLocked("login_attempts", userId, "登录失败次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
    if (!accounts.matches(str(data, "password"), user.get("password_hash"))) {
      int left = recordFailure("login_attempts", userId);
      // 未锁定时与"账号不存在"返回相同提示，避免泄露账号是否存在
      if (left > 0) throw ApiException.unauthorized("账号或密码错误");
      throw ApiException.tooMany("密码连续错误 " + MAX_FAILURES + " 次，已暂时锁定 " + LOCK_MINUTES + " 分钟");
    }
    if (sessions.isDisabled(userId)) throw ApiException.forbidden("账号已被停用");
    db.update("DELETE FROM login_attempts WHERE user_id=?", userId);
    sessions.start(userId, req, res);
    return Map.of("user", views.selfView(user));
  }

  @PostMapping("/logout")
  Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
    sessions.sameOrigin(req);
    sessions.end(req, res);
    return Map.of("ok", true);
  }

  @GetMapping("/me")
  Map<String, Object> me(HttpServletRequest req) {
    return Map.of("user", views.selfView(sessions.requireUser(req)), "days", Inputs.DAYS, "categories", NeedController.CATEGORIES);
  }

  @PatchMapping("/password")
  Map<String, Object> password(@RequestBody Map<String, Object> data, HttpServletRequest req) {
    sessions.sameOrigin(req);
    Map<String, Object> user = sessions.requireUser(req);
    String current = str(data, "current_password"), next = str(data, "new_password");
    if (!accounts.matches(current, user.get("password_hash"))) throw ApiException.bad("当前密码不正确");
    require(next, 8, 100, "新密码须为 8–100 位");
    if (current.equals(next)) throw ApiException.bad("新密码不能与当前密码相同");
    db.update("UPDATE users SET password_hash=? WHERE id=?", accounts.encode(next), Db.id(user));
    db.update("DELETE FROM sessions WHERE user_id=? AND token_hash<>?", Db.id(user), SessionService.hash(sessions.token(req)));
    return Map.of("ok", true);
  }

  // ---------- 密保问题 ----------

  private static String normalizedAnswer(Object value) {
    return String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  @Transactional
  @PostMapping("/security-questions")
  Map<String, Object> setSecurityQuestions(@RequestBody Map<String, Object> data, HttpServletRequest req) {
    sessions.sameOrigin(req);
    Map<String, Object> user = sessions.requireUser(req);
    if (!accounts.matches(str(data, "password"), user.get("password_hash"))) throw ApiException.bad("当前密码不正确");
    if (!(data.get("questions") instanceof List<?> list) || list.size() != 3) throw ApiException.bad("请设置 3 个密保问题");
    List<String> prompts = new ArrayList<>(), answers = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> question)) throw ApiException.bad("密保问题格式不正确");
      String prompt = String.valueOf(question.get("question") == null ? "" : question.get("question")).trim();
      String answer = normalizedAnswer(question.get("answer"));
      require(prompt, 4, 100, "每个问题须为 4–100 字");
      require(answer, 2, 100, "每个答案须为 2–100 字");
      if (prompts.contains(prompt)) throw ApiException.bad("三个问题不能相同");
      prompts.add(prompt);
      answers.add(answer);
    }
    long userId = Db.id(user);
    db.update("DELETE FROM security_questions WHERE user_id=?", userId);
    for (int i = 0; i < 3; i++) {
      db.update("INSERT INTO security_questions(user_id,question_no,question,answer_hash) VALUES(?,?,?,?)", userId, i + 1, prompts.get(i), accounts.encode(answers.get(i)));
    }
    db.update("DELETE FROM security_attempts WHERE user_id=?", userId);
    return Map.of("ok", true);
  }

  @GetMapping("/security-questions")
  Map<String, Object> getSecurityQuestions(@RequestParam String account) {
    Map<String, Object> user = accounts.find(account);
    // 账号不存在与未设置密保返回同一提示，避免借此探测账号是否存在
    if (user == null) throw ApiException.missing("该账号未设置密保问题");
    List<Map<String, Object>> questions = db.rows("SELECT question_no,question FROM security_questions WHERE user_id=? ORDER BY question_no", Db.id(user));
    if (questions.size() != 3) throw ApiException.missing("该账号未设置密保问题");
    return Map.of("questions", questions);
  }

  // 不加 @Transactional：答错时抛出异常，失败次数仍需写入数据库
  @PostMapping("/reset-password")
  Map<String, Object> resetPassword(@RequestBody Map<String, Object> data, HttpServletRequest req) {
    sessions.sameOrigin(req);
    String next = require(str(data, "new_password"), 8, 100, "新密码须为 8–100 位");
    Map<String, Object> user = accounts.find(str(data, "account"));
    if (user == null) throw ApiException.bad("账号或密保答案不正确");
    long userId = Db.id(user);
    ensureNotLocked("security_attempts", userId, "尝试过多，请 " + LOCK_MINUTES + " 分钟后再试");
    if (!(data.get("answers") instanceof List<?> answers) || answers.size() != 3) throw ApiException.bad("请回答 3 个密保问题");
    List<Map<String, Object>> questions = db.rows("SELECT question_no,answer_hash FROM security_questions WHERE user_id=? ORDER BY question_no", userId);
    if (questions.size() != 3) throw ApiException.bad("该账号未设置密保问题");
    int correct = 0;
    for (int i = 0; i < 3; i++) {
      String answer = normalizedAnswer(answers.get(i));
      if (!answer.isEmpty() && accounts.matches(answer, questions.get(i).get("answer_hash"))) correct++;
    }
    if (correct < 2) {
      recordFailure("security_attempts", userId);
      throw ApiException.bad("密保答案未答对至少 2 个");
    }
    db.update("UPDATE users SET password_hash=? WHERE id=?", accounts.encode(next), userId);
    db.update("DELETE FROM sessions WHERE user_id=?", userId);
    db.update("DELETE FROM security_attempts WHERE user_id=?", userId);
    db.update("DELETE FROM login_attempts WHERE user_id=?", userId);
    return Map.of("ok", true);
  }

  // ---------- 失败次数限制（table 只会是常量表名，不来自用户输入） ----------

  private void ensureNotLocked(String table, long userId, String message) {
    Map<String, Object> attempt = db.row("SELECT locked_until FROM " + table + " WHERE user_id=?", userId);
    if (attempt != null && attempt.get("locked_until") instanceof Timestamp until && until.toLocalDateTime().isAfter(LocalDateTime.now())) {
      throw ApiException.tooMany(message);
    }
  }

  /** 记录一次失败，返回锁定前剩余可尝试次数；达到上限时锁定并清零计数。 */
  private int recordFailure(String table, long userId) {
    Map<String, Object> attempt = db.row("SELECT failures FROM " + table + " WHERE user_id=?", userId);
    int failures = attempt == null ? 1 : ((Number) attempt.get("failures")).intValue() + 1;
    boolean lock = failures >= MAX_FAILURES;
    db.update("DELETE FROM " + table + " WHERE user_id=?", userId);
    db.update("INSERT INTO " + table + "(user_id,failures,locked_until) VALUES(?,?,?)", userId, lock ? 0 : failures,
        lock ? Timestamp.valueOf(LocalDateTime.now().plusMinutes(LOCK_MINUTES)) : null);
    return lock ? 0 : MAX_FAILURES - failures;
  }
}
