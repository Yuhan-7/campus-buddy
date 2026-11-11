package com.campusbuddies.profile;

import com.campusbuddies.auth.AccountService;
import com.campusbuddies.auth.SessionService;
import com.campusbuddies.common.Db;
import com.campusbuddies.common.Json;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户数据的对外视图。
 * 公开视图只含资料字段；联系方式只在本人视图或双方同意的邀请中出现（见 InvitationController）。
 */
@Component
public class UserViews {
  private static final List<String> PUBLIC_FIELDS = List.of("id", "name", "age", "gender", "grade", "hometown", "bio");
  private static final List<String> LIST_FIELDS = List.of("personality", "interests", "skills", "wants");
  private static final List<String> CONTACT_FIELDS = List.of("contact_email", "phone", "wechat", "qq");
  private final Json json;
  private final AccountService accounts;
  private final SessionService sessions;

  public UserViews(Json json, AccountService accounts, SessionService sessions) {
    this.json = json;
    this.accounts = accounts;
    this.sessions = sessions;
  }

  public Map<String, Object> publicView(Map<String, Object> user) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (String key : PUBLIC_FIELDS) view.put(key, user.get(key));
    for (String key : LIST_FIELDS) view.put(key, json.decode(user.get(key)));
    return view;
  }

  public Map<String, Object> selfView(Map<String, Object> user) {
    Map<String, Object> view = publicView(user);
    long userId = Db.id(user);
    view.put("email", user.get("username"));
    view.put("account_id", accounts.accountId(userId));
    view.put("admin", sessions.isAdmin(userId));
    for (String key : CONTACT_FIELDS) view.put(key, user.get(key));
    return view;
  }
}
