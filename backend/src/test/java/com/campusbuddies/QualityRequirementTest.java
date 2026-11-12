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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 质量需求验收测试。每个用例的 DisplayName 以质量需求编号开头，编号定义见 docs/质量需求与测试对照.md。
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:quality;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=always", "ADMIN_EMAIL=admin@quality.example", "ADMIN_PASSWORD=adminpassword123"})
@AutoConfigureMockMvc
class QualityRequirementTest {
  private static final String SLOT = "[{\"day\":\"周六\",\"start\":\"14:00\",\"end\":\"16:00\"}]";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private JsonNode data(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }

  private void register(String email) throws Exception {
    mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"agree_privacy\":true}")).andExpect(status().isOk());
  }

  private Cookie login(String account) throws Exception {
    MvcResult r = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"account\":\"" + account + "\",\"password\":\"password123\"}")).andExpect(status().isOk()).andReturn();
    return new Cookie("buddy_session", r.getResponse().getHeader("Set-Cookie").split("=")[1].split(";")[0]);
  }

  private long createNeed(Cookie who, String category, String title) throws Exception {
    MvcResult r = mvc.perform(post("/api/needs").cookie(who).contentType(MediaType.APPLICATION_JSON)
        .content("{\"category\":\"" + category + "\",\"title\":\"" + title + "\",\"description\":\"\",\"tags\":[],\"slots\":" + SLOT + "}"))
        .andExpect(status().isCreated()).andReturn();
    return data(r).get("id").asLong();
  }

  private void setContact(Cookie who, String name, String email) throws Exception {
    mvc.perform(patch("/api/me").cookie(who).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"" + name + "\",\"contact_email\":\"" + email + "\",\"personality\":[],\"interests\":[],\"skills\":[],\"wants\":[]}"))
        .andExpect(status().isOk());
  }

  private long myId(Cookie who) throws Exception {
    return data(mvc.perform(get("/api/me").cookie(who)).andReturn()).get("user").get("id").asLong();
  }

  @Test
  @DisplayName("QR-P1 注册前必须同意个人信息使用说明，说明清单可公开查看")
  void registrationRequiresPrivacyConsent() throws Exception {
    mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"noconsent@test.example\",\"password\":\"password123\"}")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"noconsent@test.example\",\"password\":\"password123\",\"agree_privacy\":false}")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/privacy")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").isNumber());
    register("consent@test.example");
  }

  @Test
  @DisplayName("QR-P2 注销账号：须密码与确认词，注销后资料与联系方式清除、会话失效、待处理邀请撤回")
  void accountDeletionErasesPersonalData() throws Exception {
    register("leaver@test.example");
    register("stayer@test.example");
    Cookie leaver = login("leaver@test.example"), stayer = login("stayer@test.example");
    setContact(leaver, "要离开的人", "leaver@test.example");
    setContact(stayer, "留下的人", "stayer@test.example");
    long leaverId = myId(leaver), stayerId = myId(stayer);
    long needA = createNeed(stayer, "学习", "一起准备期末");
    long needB = createNeed(stayer, "运动", "周末跑步搭子");
    long accepted = data(mvc.perform(post("/api/invitations").cookie(stayer).contentType(MediaType.APPLICATION_JSON)
        .content("{\"need_id\":" + needA + ",\"receiver_id\":" + leaverId + "}")).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    mvc.perform(patch("/api/invitations/" + accepted).cookie(leaver).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"accepted\"}")).andExpect(status().isOk());
    long pending = data(mvc.perform(post("/api/invitations").cookie(stayer).contentType(MediaType.APPLICATION_JSON)
        .content("{\"need_id\":" + needB + ",\"receiver_id\":" + leaverId + "}")).andExpect(status().isCreated()).andReturn()).get("id").asLong();

    // 二次确认：确认词错误、密码错误都不执行
    mvc.perform(delete("/api/me").cookie(leaver).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\",\"confirm\":\"注销\"}")).andExpect(status().isBadRequest());
    mvc.perform(delete("/api/me").cookie(leaver).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrongpass\",\"confirm\":\"确认注销\"}")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/me").cookie(leaver)).andExpect(status().isOk());

    mvc.perform(delete("/api/me").cookie(leaver).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\",\"confirm\":\"确认注销\"}")).andExpect(status().isOk());
    mvc.perform(get("/api/me").cookie(leaver)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"leaver@test.example\",\"password\":\"password123\"}")).andExpect(status().isUnauthorized());
    // 已同意的匹配记录保留，但注销方联系方式已清空
    mvc.perform(get("/api/contacts/" + accepted).cookie(stayer)).andExpect(status().isOk())
        .andExpect(jsonPath("$.contacts[1].email").value(""))
        .andExpect(jsonPath("$.contacts[1].name").value("已注销用户#" + leaverId));
    // 待处理邀请已撤回，不能再被处理
    mvc.perform(patch("/api/invitations/" + pending).cookie(stayer).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"withdrawn\"}")).andExpect(status().isConflict());
    // 注销后的公开资料不再含任何个人信息
    mvc.perform(get("/api/users/" + leaverId).cookie(stayer)).andExpect(status().isOk()).andExpect(jsonPath("$.user.bio").value(""));
    assertTrue(stayerId != leaverId);
  }

  @Test
  @DisplayName("QR-U1 未登录可浏览需求广场、详情与热门主题，但不能发布或邀请")
  void guestCanBrowseButNotWrite() throws Exception {
    register("poster@test.example");
    Cookie poster = login("poster@test.example");
    long needId = createNeed(poster, "兴趣", "访客可见的摄影搭子");
    mvc.perform(get("/api/needs")).andExpect(status().isOk()).andExpect(jsonPath("$.needs").isArray());
    mvc.perform(get("/api/trending")).andExpect(status().isOk());
    // 访客查看详情不计浏览量
    mvc.perform(get("/api/needs/" + needId)).andExpect(status().isOk()).andExpect(jsonPath("$.need.views").value(0));
    mvc.perform(post("/api/needs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"category\":\"学习\",\"title\":\"未登录发布\",\"tags\":[],\"slots\":" + SLOT + "}")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/invitations").contentType(MediaType.APPLICATION_JSON).content("{\"need_id\":" + needId + ",\"receiver_id\":1}")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/users/1")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("QR-S1 密码连续错误 5 次后锁定，锁定期间正确密码也无法登录")
  void loginLocksAfterRepeatedFailures() throws Exception {
    register("brute@test.example");
    String wrong = "{\"account\":\"brute@test.example\",\"password\":\"wrongpass\"}";
    for (int i = 0; i < 4; i++) {
      mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(wrong)).andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(wrong)).andExpect(status().isTooManyRequests());
    mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"brute@test.example\",\"password\":\"password123\"}")).andExpect(status().isTooManyRequests());
    // 不存在的账号与密码错误提示一致，不泄露账号是否存在
    mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"nobody@test.example\",\"password\":\"wrongpass\"}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("账号或密码错误"));
  }

  @Test
  @DisplayName("QR-S2 跨站来源的写请求被拒绝")
  void crossOriginWriteRejected() throws Exception {
    mvc.perform(post("/api/register").header("Origin", "https://evil.example").header("Host", "localhost:8088").contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"csrf@test.example\",\"password\":\"password123\",\"agree_privacy\":true}")).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("QR-F1 广场按类型与关键词在数据库中筛选并分页，通配符按字面匹配")
  void plazaFiltersInDatabase() throws Exception {
    register("filter@test.example");
    Cookie who = login("filter@test.example");
    createNeed(who, "运动", "唯一关键词羽毛球ZQX");
    createNeed(who, "学习", "唯一关键词高数ZQX");
    MvcResult sport = mvc.perform(get("/api/needs").param("q", "zqx").param("category", "运动")).andExpect(status().isOk()).andReturn();
    assertEquals(1, data(sport).get("needs").size());
    MvcResult both = mvc.perform(get("/api/needs").param("q", "ZQX")).andExpect(status().isOk()).andReturn();
    assertEquals(2, data(both).get("needs").size());
    // “%” 不应被当作通配符而匹配全部需求
    MvcResult wildcard = mvc.perform(get("/api/needs").param("q", "%")).andExpect(status().isOk()).andReturn();
    assertEquals(0, data(wildcard).get("needs").size());
    mvc.perform(get("/api/needs").param("category", "不存在的类型")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("QR-F2 需求关闭后可重新开放（可撤销），删除后不可再操作")
  void closeIsUndoableDeleteIsFinal() throws Exception {
    register("undo@test.example");
    Cookie who = login("undo@test.example");
    long needId = createNeed(who, "学习", "可以撤销的需求");
    mvc.perform(patch("/api/needs/" + needId).cookie(who).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"closed\"}")).andExpect(status().isOk());
    mvc.perform(get("/api/needs/" + needId)).andExpect(status().isNotFound());
    mvc.perform(patch("/api/needs/" + needId).cookie(who).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"open\"}")).andExpect(status().isOk());
    mvc.perform(get("/api/needs/" + needId)).andExpect(status().isOk());
    mvc.perform(delete("/api/needs/" + needId).cookie(who)).andExpect(status().isOk());
    mvc.perform(patch("/api/needs/" + needId).cookie(who).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"open\"}")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("QR-F4 拒绝邀请可附原因（最多 100 字），发送方能看到原因")
  void rejectWithReason() throws Exception {
    register("asker@test.example");
    register("owner2@test.example");
    Cookie asker = login("asker@test.example"), owner = login("owner2@test.example");
    setContact(asker, "申请人乙", "asker@test.example");
    setContact(owner, "发布者乙", "owner2@test.example");
    long needId = createNeed(owner, "运动", "需要拒绝原因的需求");
    long invitationId = data(mvc.perform(post("/api/invitations").cookie(asker).contentType(MediaType.APPLICATION_JSON)
        .content("{\"need_id\":" + needId + ",\"receiver_id\":" + myId(owner) + "}")).andExpect(status().isCreated()).andReturn()).get("id").asLong();
    mvc.perform(patch("/api/invitations/" + invitationId).cookie(owner).contentType(MediaType.APPLICATION_JSON)
        .content("{\"action\":\"rejected\",\"reason\":\"" + "长".repeat(101) + "\"}")).andExpect(status().isBadRequest());
    mvc.perform(patch("/api/invitations/" + invitationId).cookie(owner).contentType(MediaType.APPLICATION_JSON)
        .content("{\"action\":\"rejected\",\"reason\":\"时间对不上\"}")).andExpect(status().isOk());
    mvc.perform(get("/api/invitations").cookie(asker)).andExpect(status().isOk())
        .andExpect(jsonPath("$.invitations[0].status").value("rejected"))
        .andExpect(jsonPath("$.invitations[0].reason").value("时间对不上"));
  }

  @Test
  @DisplayName("QR-A4 智能填写需要登录；发布含敏感词的帖子时管理端能看到疑似违规提示")
  void aiDraftAndModerationNote() throws Exception {
    mvc.perform(post("/api/ai/need-draft").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"周六打羽毛球\"}")).andExpect(status().isUnauthorized());
    register("aiuser@test.example");
    Cookie who = login("aiuser@test.example");
    mvc.perform(post("/api/ai/need-draft").cookie(who).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"周六下午想打羽毛球\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.draft.category").value("运动")).andExpect(jsonPath("$.draft.day").value("周六"));
    createNeed(who, "技能互助", "有偿代写实验报告");
    MvcResult adminLogin = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"account\":\"admin@quality.example\",\"password\":\"adminpassword123\"}")).andExpect(status().isOk()).andReturn();
    Cookie admin = new Cookie("buddy_session", adminLogin.getResponse().getHeader("Set-Cookie").split("=")[1].split(";")[0]);
    String overview = mvc.perform(get("/api/admin/overview").cookie(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertTrue(overview.contains("含敏感词：有偿、代写"), "管理端应看到疑似违规提示");
  }

  @Test
  @DisplayName("QR-F3 未填写联系方式不能发出邀请，并提示去个人资料填写")
  void inviteRequiresContact() throws Exception {
    register("nocontact@test.example");
    register("host@test.example");
    Cookie guest = login("nocontact@test.example"), host = login("host@test.example");
    setContact(host, "发布者甲", "host@test.example");
    long needId = createNeed(host, "学习", "需要联系方式的需求");
    long hostId = myId(host);
    mvc.perform(post("/api/invitations").cookie(guest).contentType(MediaType.APPLICATION_JSON).content("{\"need_id\":" + needId + ",\"receiver_id\":" + hostId + "}"))
        .andExpect(status().isBadRequest());
  }
}
