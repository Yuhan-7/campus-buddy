package com.campusbuddies.admin;

import com.campusbuddies.auth.AccountService;
import com.campusbuddies.common.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * 启动时执行：
 * 1. 旧数据库补列、补账号 ID 与唯一昵称（兼容早期版本数据）；
 * 2. 按 .env 中的 ADMIN_EMAIL / ADMIN_PASSWORD 初始化管理员（只执行一次）。
 */
@Component
@Order(1)
public class StartupInitializer implements ApplicationRunner {
  private final Db db;
  private final AccountService accounts;
  @Value("${ADMIN_EMAIL:}") private String adminEmail;
  @Value("${ADMIN_PASSWORD:}") private String adminPassword;

  public StartupInitializer(Db db, AccountService accounts) {
    this.db = db;
    this.accounts = accounts;
  }

  @Override
  public void run(ApplicationArguments args) {
    migrateLegacyUsers();
    bootstrapAdmin();
  }

  private void migrateLegacyUsers() {
    try {
      db.row("SELECT gender FROM users LIMIT 1");
    } catch (DataAccessException e) {
      db.execute("ALTER TABLE users ADD COLUMN gender VARCHAR(12) NOT NULL DEFAULT ''");
    }
    try {
      db.row("SELECT reason FROM invitations LIMIT 1");
    } catch (DataAccessException e) {
      db.execute("ALTER TABLE invitations ADD COLUMN reason VARCHAR(200) NOT NULL DEFAULT ''");
    }
    for (Map<String, Object> u : db.rows("SELECT id,name,created_at FROM users ORDER BY id")) {
      long userId = Db.id(u);
      if (accounts.accountId(userId).isEmpty()) {
        LocalDate date = u.get("created_at") instanceof Timestamp t ? t.toLocalDateTime().toLocalDate() : LocalDate.now(AccountService.ZONE);
        accounts.newAccountId(userId, date);
      }
      if (!db.exists("SELECT user_id FROM unique_nicknames WHERE user_id=?", userId)) {
        String name = String.valueOf(u.get("name"));
        if (accounts.nicknameTaken(name)) {
          name = accounts.newNickname();
          db.update("UPDATE users SET name=? WHERE id=?", name, userId);
        }
        db.update("INSERT INTO unique_nicknames(user_id,nickname) VALUES(?,?)", userId, name);
      }
    }
  }

  private void bootstrapAdmin() {
    if (adminEmail.isBlank() || adminPassword.isBlank()) return;
    String email = adminEmail.trim().toLowerCase(Locale.ROOT);
    Map<String, Object> existing = db.row("SELECT * FROM users WHERE username=?", email);
    long adminId;
    if (existing == null) {
      String name = accounts.nicknameTaken("系统管理员") ? accounts.newNickname() : "系统管理员";
      adminId = accounts.create(email, adminPassword, name);
    } else {
      adminId = Db.id(existing);
    }
    if (!db.exists("SELECT user_id FROM admins WHERE user_id=?", adminId)) db.update("INSERT INTO admins(user_id) VALUES(?)", adminId);
    // 只在第一次初始化时同步密码，之后在网站中修改的密码不会被 .env 覆盖
    if (!db.exists("SELECT user_id FROM admin_bootstrap_passwords WHERE user_id=?", adminId)) {
      db.update("UPDATE users SET password_hash=? WHERE id=?", accounts.encode(adminPassword), adminId);
      db.update("DELETE FROM sessions WHERE user_id=?", adminId);
      db.update("INSERT INTO admin_bootstrap_passwords(user_id) VALUES(?)", adminId);
    }
  }
}
