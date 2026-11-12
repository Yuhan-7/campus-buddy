package com.campusbuddies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 黑盒测试（第 7 章）：只看需求规格中的输入与预期输出，不依赖内部实现。
 * 用例设计方法：等价类划分（有效类 / 无效类）+ 边界值分析（边界及其两侧）+ 状态转换与权限。
 * 用例编号 TC-xx 与 docs/质量需求与测试对照.md 中的测试设计表一致。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:blackbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=always", "ADMIN_EMAIL=admin@blackbox.example", "ADMIN_PASSWORD=adminpassword123"})
@AutoConfigureMockMvc
class BlackBoxTest {
  private static final String SLOT = "[{\"day\":\"周六\",\"start\":\"14:00\",\"end\":\"16:00\"}]";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  // ---------- 辅助方法 ----------

  private JsonNode data(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }

  private MvcResult registerRaw(String email, String password) throws Exception {
    return mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"agree_privacy\":true}")).andReturn();
  }

  private Cookie login(String account, String password) throws Exception {
    MvcResult r = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}")).andExpect(status().isOk()).andReturn();
    return new Cookie("buddy_session", r.getResponse().getHeader("Set-Cookie").split("=")[1].split(";")[0]);
  }

  /** 注册、登录并填写联系方式，返回登录 Cookie */
  private Cookie user(String email, String nickname) throws Exception {
    registerRaw(email, "password123");
    Cookie c = login(email, "password123");
    profile(c, "{\"name\":\"" + nickname + "\",\"contact_email\":\"" + email + "\",\"personality\":[],\"interests\":[],\"skills\":[],\"wants\":[]}")
        .andExpect(status().isOk());
    return c;
  }

  private org.springframework.test.web.servlet.ResultActions profile(Cookie c, String body) throws Exception {
    return mvc.perform(patch("/api/me").cookie(c).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private org.springframework.test.web.servlet.ResultActions publish(Cookie c, String title) throws Exception {
    return mvc.perform(post("/api/needs").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"category\":\"运动\",\"title\":\"" + title + "\",\"description\":\"\",\"tags\":[],\"slots\":" + SLOT + "}"));
  }

  private long needOf(Cookie c, String title) throws Exception {
    return data(publish(c, title).andExpect(status().isCreated()).andReturn()).get("id").asLong();
  }

  private long idOf(Cookie c) throws Exception {
    return data(mvc.perform(get("/api/me").cookie(c)).andReturn()).get("user").get("id").asLong();
  }

  private org.springframework.test.web.servlet.ResultActions invite(Cookie from, long needId, long to) throws Exception {
    return mvc.perform(post("/api/invitations").cookie(from).contentType(MediaType.APPLICATION_JSON)
        .content("{\"need_id\":" + needId + ",\"receiver_id\":" + to + "}"));
  }

  private org.springframework.test.web.servlet.ResultActions act(Cookie who, long invitationId, String action) throws Exception {
    return mvc.perform(patch("/api/invitations/" + invitationId).cookie(who).contentType(MediaType.APPLICATION_JSON)
        .content("{\"action\":\"" + action + "\"}"));
  }

  // ---------- 用例 ----------

  @Test
  @DisplayName("TC-01 边界值：注册密码 7 位、21 位被拒绝，8 位、20 位通过")
  void passwordLengthBoundaries() throws Exception {
    assertStatus(registerRaw("pw7@test.example", "a".repeat(7)), 400);
    assertStatus(registerRaw("pw8@test.example", "a".repeat(8)), 200);
    assertStatus(registerRaw("pw20@test.example", "a".repeat(20)), 200);
    assertStatus(registerRaw("pw21@test.example", "a".repeat(21)), 400);
  }

  @Test
  @DisplayName("TC-02 边界值：需求标题 2 字、61 字被拒绝，3 字、60 字通过")
  void titleLengthBoundaries() throws Exception {
    Cookie c = user("title@test.example", "标题测试者");
    publish(c, "羽毛").andExpect(status().isBadRequest());
    publish(c, "羽毛球").andExpect(status().isCreated());
    publish(c, "长".repeat(60)).andExpect(status().isCreated());
    publish(c, "长".repeat(61)).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("TC-03 等价类：个人资料年龄 15、101、QQ 4 位为无效类被拒绝，年龄 16、QQ 5 位为有效类通过")
  void profileEquivalenceClasses() throws Exception {
    Cookie c = user("profile@test.example", "资料测试者");
    String base = "\"name\":\"资料测试者\",\"contact_email\":\"profile@test.example\",\"personality\":[],\"interests\":[],\"skills\":[],\"wants\":[]";
    profile(c, "{" + base + ",\"age\":\"15\"}").andExpect(status().isBadRequest());
    profile(c, "{" + base + ",\"age\":\"101\"}").andExpect(status().isBadRequest());
    profile(c, "{" + base + ",\"age\":\"16\"}").andExpect(status().isOk());
    profile(c, "{" + base + ",\"qq\":\"1234\"}").andExpect(status().isBadRequest());
    profile(c, "{" + base + ",\"qq\":\"12345\"}").andExpect(status().isOk());
  }

  @Test
  @DisplayName("TC-04 异常情况：昵称与他人重复时被拒绝")
  void duplicateNickname() throws Exception {
    user("nick1@test.example", "独一无二");
    Cookie second = user("nick2@test.example", "另一个人");
    profile(second, "{\"name\":\"独一无二\",\"personality\":[],\"interests\":[],\"skills\":[],\"wants\":[]}").andExpect(status().isConflict());
  }

  @Test
  @DisplayName("TC-05 异常情况：对同一需求重复邀请同一个人，第二次被拒绝")
  void duplicateInvitation() throws Exception {
    Cookie owner = user("dup-owner@test.example", "重复邀请发布者");
    Cookie asker = user("dup-asker@test.example", "重复邀请申请者");
    long needId = needOf(owner, "重复邀请测试需求");
    invite(asker, needId, idOf(owner)).andExpect(status().isCreated());
    invite(asker, needId, idOf(owner)).andExpect(status().isConflict());
  }

  @Test
  @DisplayName("TC-06 状态转换：接收方不能撤回、发送方不能同意；已同意的邀请不能再次处理")
  void invitationTransitions() throws Exception {
    Cookie owner = user("st-owner@test.example", "状态发布者");
    Cookie asker = user("st-asker@test.example", "状态申请者");
    long needId = needOf(owner, "状态转换测试需求");
    long inv = data(invite(asker, needId, idOf(owner)).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    act(owner, inv, "withdrawn").andExpect(status().isForbidden());
    act(asker, inv, "accepted").andExpect(status().isForbidden());
    act(owner, inv, "accepted").andExpect(status().isOk());
    act(owner, inv, "rejected").andExpect(status().isConflict());
  }

  @Test
  @DisplayName("TC-07 权限：不能为别人的需求请求推荐；关闭的需求不能匹配")
  void recommendPermissions() throws Exception {
    Cookie owner = user("rec-owner@test.example", "推荐发布者");
    Cookie other = user("rec-other@test.example", "推荐旁观者");
    long needId = needOf(owner, "推荐权限测试需求");
    mvc.perform(get("/api/recommend/" + needId).cookie(other)).andExpect(status().isForbidden());
    mvc.perform(patch("/api/needs/" + needId).cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"closed\"}")).andExpect(status().isOk());
    mvc.perform(get("/api/recommend/" + needId).cookie(owner)).andExpect(status().isConflict());
  }

  @Test
  @DisplayName("TC-08 平台管理：管理员停用账号后该用户被退出且无法登录，恢复后可以登录")
  void disableAndEnable() throws Exception {
    Cookie target = user("disable@test.example", "被停用的人");
    long targetId = idOf(target);
    Cookie admin = login("admin@blackbox.example", "adminpassword123");
    mvc.perform(patch("/api/admin/users/" + targetId).cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"disable\"}")).andExpect(status().isOk());
    mvc.perform(get("/api/me").cookie(target)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"account\":\"disable@test.example\",\"password\":\"password123\"}")).andExpect(status().isForbidden());
    mvc.perform(patch("/api/admin/users/" + targetId).cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"enable\"}")).andExpect(status().isOk());
    login("disable@test.example", "password123");
  }

  @Test
  @DisplayName("TC-09 隐私：管理员看不到用户联系方式，也不能查看他人邀请中的联系方式")
  void adminCannotSeeContacts() throws Exception {
    Cookie owner = user("priv-owner@test.example", "隐私发布者");
    Cookie asker = user("priv-asker@test.example", "隐私申请者");
    long needId = needOf(owner, "隐私测试需求");
    long inv = data(invite(asker, needId, idOf(owner)).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    act(owner, inv, "accepted").andExpect(status().isOk());
    Cookie admin = login("admin@blackbox.example", "adminpassword123");
    String overview = mvc.perform(get("/api/admin/overview").cookie(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertFalse(overview.contains("priv-asker@test.example"), "管理端不应出现用户联系方式");
    mvc.perform(get("/api/contacts/" + inv).cookie(admin)).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("TC-10 异常情况：修改密码时当前密码错误、新密码与旧密码相同均被拒绝")
  void changePasswordErrors() throws Exception {
    Cookie c = user("chpw@test.example", "改密码的人");
    mvc.perform(patch("/api/password").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"current_password\":\"wrongpass1\",\"new_password\":\"newpassword1\"}")).andExpect(status().isBadRequest());
    mvc.perform(patch("/api/password").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"current_password\":\"password123\",\"new_password\":\"password123\"}")).andExpect(status().isBadRequest());
    mvc.perform(patch("/api/password").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"current_password\":\"password123\",\"new_password\":\"newpassword1\"}")).andExpect(status().isOk());
    login("chpw@test.example", "newpassword1");
  }

  private static void assertStatus(MvcResult r, int expected) {
    int actual = r.getResponse().getStatus();
    if (actual != expected) throw new AssertionError("期望状态码 " + expected + "，实际 " + actual);
  }
}
