package com.campusbuddies.auth;

import com.campusbuddies.common.ApiException;
import com.campusbuddies.common.Db;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/** 会话与权限：Cookie 中只保存随机令牌，数据库只保存令牌的 SHA-256。 */
@Component
public class SessionService {
  public static final String COOKIE = "buddy_session";
  private static final int SESSION_SECONDS = 7 * 24 * 3600;
  private final Db db;
  private final SecureRandom random = new SecureRandom();

  public SessionService(Db db) {
    this.db = db;
  }

  public static String hash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String token(HttpServletRequest req) {
    if (req.getCookies() == null) return "";
    for (Cookie cookie : req.getCookies()) if (COOKIE.equals(cookie.getName())) return cookie.getValue();
    return "";
  }

  /** 新建会话并写入 HttpOnly、SameSite=Strict 的 Cookie。 */
  public void start(long userId, HttpServletRequest req, HttpServletResponse res) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)",
        hash(token), userId, Timestamp.valueOf(LocalDateTime.now().plusSeconds(SESSION_SECONDS)));
    writeCookie(req, res, token, SESSION_SECONDS);
  }

  public void end(HttpServletRequest req, HttpServletResponse res) {
    String token = token(req);
    if (!token.isEmpty()) db.update("DELETE FROM sessions WHERE token_hash=?", hash(token));
    writeCookie(req, res, "", 0);
  }

  private void writeCookie(HttpServletRequest req, HttpServletResponse res, String token, int maxAge) {
    String value = COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge + (req.isSecure() ? "; Secure" : "");
    res.addHeader("Set-Cookie", value);
  }

  /** 当前登录用户；未登录返回 null（用于免注册浏览）。 */
  public Map<String, Object> optionalUser(HttpServletRequest req) {
    String token = token(req);
    if (token.isEmpty()) return null;
    Map<String, Object> user = db.row("SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>NOW()", hash(token));
    if (user == null || isDisabled(Db.id(user))) return null;
    return user;
  }

  /** 当前登录用户；未登录返回 401，被停用返回 403。 */
  public Map<String, Object> requireUser(HttpServletRequest req) {
    String token = token(req);
    if (token.isEmpty()) throw ApiException.unauthorized("请先登录");
    Map<String, Object> user = db.row("SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>NOW()", hash(token));
    if (user == null) throw ApiException.unauthorized("登录已过期，请重新登录");
    if (isDisabled(Db.id(user))) throw ApiException.forbidden("账号已被停用");
    return user;
  }

  public Map<String, Object> requireAdmin(HttpServletRequest req) {
    Map<String, Object> user = requireUser(req);
    if (!isAdmin(Db.id(user))) throw ApiException.forbidden();
    return user;
  }

  public boolean isAdmin(long userId) {
    return db.exists("SELECT user_id FROM admins WHERE user_id=?", userId);
  }

  public boolean isDisabled(long userId) {
    return db.exists("SELECT user_id FROM disabled_users WHERE user_id=?", userId);
  }

  public boolean isDeleted(long userId) {
    return db.exists("SELECT user_id FROM deleted_users WHERE user_id=?", userId);
  }

  /** 写操作只接受同源请求，防止跨站请求伪造。 */
  public void sameOrigin(HttpServletRequest req) {
    String origin = req.getHeader("Origin");
    if (origin == null) return;
    try {
      String host = req.getHeader("Host");
      if (host == null || !host.equalsIgnoreCase(URI.create(origin).getAuthority())) throw ApiException.forbidden();
    } catch (IllegalArgumentException e) {
      throw ApiException.forbidden();
    }
  }
}
