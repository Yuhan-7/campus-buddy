package com.campusbuddies.auth;

import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** 账号相关的公共逻辑：建号、账号 ID、唯一昵称、密码哈希。 */
@Component
public class AccountService {
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private final Db db;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final SecureRandom random = new SecureRandom();

  public AccountService(Db db) {
    this.db = db;
  }

  public String encode(String raw) {
    return passwords.encode(raw);
  }

  public boolean matches(String raw, Object hash) {
    return hash != null && passwords.matches(raw, String.valueOf(hash));
  }

  /** 新建用户，同时分配账号 ID 与唯一昵称，返回用户主键。 */
  public long create(String email, String rawPassword, String nickname) {
    long userId;
    try {
      userId = db.insert("INSERT INTO users(username,password_hash,name,personality,interests,skills,wants) VALUES(?,?,?,?,?,?,?)",
          email, encode(rawPassword), nickname, "[]", "[]", "[]", "[]");
    } catch (DuplicateKeyException e) {
      throw ApiException.conflict("该邮箱已注册");
    }
    newAccountId(userId, LocalDate.now(ZONE));
    db.update("INSERT INTO unique_nicknames(user_id,nickname) VALUES(?,?)", userId, nickname);
    return userId;
  }

  /** 按邮箱、账号 ID 或内部编号查找用户。 */
  public Map<String, Object> find(String input) {
    String value = input.trim().toLowerCase(Locale.ROOT);
    if (value.matches("[1-9][0-9]{0,17}")) {
      Map<String, Object> user = db.row("SELECT u.* FROM users u JOIN account_ids a ON a.user_id=u.id WHERE a.account_id=?", value);
      if (user != null) return user;
      return db.row("SELECT * FROM users WHERE id=?", Long.parseLong(value));
    }
    return db.row("SELECT * FROM users WHERE username=?", value);
  }

  public String accountId(long userId) {
    Map<String, Object> row = db.row("SELECT account_id FROM account_ids WHERE user_id=?", userId);
    return row == null ? "" : String.valueOf(row.get("account_id"));
  }

  /** 账号 ID = 注册日期(yyyyMMdd) + 6 位随机数，冲突时重试。 */
  public String newAccountId(long userId, LocalDate date) {
    String prefix = date.format(DateTimeFormatter.BASIC_ISO_DATE);
    for (int i = 0; i < 30; i++) {
      String value = prefix + String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
      try {
        db.update("INSERT INTO account_ids(user_id,account_id) VALUES(?,?)", userId, value);
        return value;
      } catch (DuplicateKeyException e) {
        if (db.exists("SELECT user_id FROM account_ids WHERE user_id=?", userId)) return accountId(userId);
      }
    }
    throw new IllegalStateException("无法生成唯一账号 ID");
  }

  public String newNickname() {
    for (int i = 0; i < 30; i++) {
      String name = "同频同学" + String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
      if (!nicknameTaken(name)) return name;
    }
    throw new IllegalStateException("无法生成唯一昵称");
  }

  public boolean nicknameTaken(String name) {
    return db.exists("SELECT user_id FROM unique_nicknames WHERE nickname=?", name);
  }

  public static boolean hasContact(Map<String, Object> user) {
    for (String key : new String[] {"contact_email", "phone", "wechat", "qq"}) {
      Object value = user.get(key);
      if (value != null && !String.valueOf(value).isBlank()) return true;
    }
    return false;
  }

  public SecureRandom random() {
    return random;
  }
}
