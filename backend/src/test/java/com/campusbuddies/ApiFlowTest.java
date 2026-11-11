package com.campusbuddies;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:buddy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.sql.init.mode=always","ADMIN_EMAIL=admin@test.example","ADMIN_PASSWORD=adminpassword123"})
@AutoConfigureMockMvc
class ApiFlowTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json;
 private JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString());}
 private void register(String email)throws Exception{mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"password\":\"password123\",\"agree_privacy\":true}")).andExpect(status().isOk());}
 private Cookie login(String account)throws Exception{MvcResult r=mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\""+account+"\",\"password\":\"password123\"}")).andExpect(status().isOk()).andReturn();return new Cookie("buddy_session",r.getResponse().getHeader("Set-Cookie").split("=")[1].split(";")[0]);}
 @Test void registrationAndAdminAccess()throws Exception{
  mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"bad..mail@test.example\",\"password\":\"password123\",\"agree_privacy\":true}")).andExpect(status().isBadRequest());
  register("member@test.example");Cookie member=login("member@test.example");mvc.perform(get("/api/admin/overview").cookie(member)).andExpect(status().isForbidden());
  MvcResult r=mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin@test.example\",\"password\":\"adminpassword123\"}")).andExpect(status().isOk()).andReturn();Cookie admin=new Cookie("buddy_session",r.getResponse().getHeader("Set-Cookie").split("=")[1].split(";")[0]);mvc.perform(get("/api/admin/overview").cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.users.length()").isNumber());
 }
 @Test void moderationViewsAndPrivateMessages()throws Exception{
  register("author@test.example");Cookie author=login("author@test.example");
  MvcResult created=mvc.perform(post("/api/needs").cookie(author).contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"学习\",\"title\":\"测试浏览量\",\"description\":\"图书馆\",\"tags\":[],\"slots\":[{\"day\":\"周六\",\"start\":\"14:00\",\"end\":\"16:00\"}]}")).andExpect(status().isCreated()).andReturn();long needId=data(created).get("id").asLong();
  mvc.perform(get("/api/needs/"+needId).cookie(author)).andExpect(status().isOk()).andExpect(jsonPath("$.need.views").value(1));
  mvc.perform(get("/api/needs/"+needId).cookie(author)).andExpect(status().isOk()).andExpect(jsonPath("$.need.views").value(1));
  mvc.perform(get("/api/trending").cookie(author)).andExpect(status().isOk());
  MvcResult loginAdmin=mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin@test.example\",\"password\":\"adminpassword123\"}")).andExpect(status().isOk()).andReturn();Cookie admin=new Cookie("buddy_session",loginAdmin.getResponse().getHeader("Set-Cookie").split("=")[1].split(";")[0]);
  MvcResult overview=mvc.perform(get("/api/admin/overview").cookie(admin)).andExpect(status().isOk()).andReturn();if(data(overview).get("users").get(0).has("contact_email"))throw new AssertionError("admin contact leak");
  mvc.perform(post("/api/admin/messages").cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"need_id\":"+needId+",\"body\":\"请检查帖子内容\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/messages").cookie(author)).andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].body").value("请检查帖子内容"));
  mvc.perform(patch("/api/admin/needs/"+needId).cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"remove\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/needs/"+needId).cookie(author)).andExpect(status().isNotFound());
  mvc.perform(patch("/api/needs/"+needId).cookie(author).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"open\"}")).andExpect(status().isConflict());
 }
 @Test void idLoginAndSecurityQuestions()throws Exception{
  register("recover@test.example");Cookie original=login("recover@test.example");JsonNode self=data(mvc.perform(get("/api/me").cookie(original)).andReturn()).get("user");long id=self.get("id").asLong();String accountId=self.get("account_id").asText();if(!accountId.matches("[0-9]{14}"))throw new AssertionError("public account ID format");login(accountId);
  mvc.perform(post("/api/security-questions").cookie(original).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\",\"questions\":[{\"question\":\"我的第一本书叫什么\",\"answer\":\"蓝星\"},{\"question\":\"我最喜欢的食物是什么\",\"answer\":\"面条\"},{\"question\":\"我的童年玩具叫什么\",\"answer\":\"小熊\"}]}")).andExpect(status().isOk());
  mvc.perform(get("/api/security-questions").param("account",String.valueOf(id))).andExpect(status().isOk()).andExpect(jsonPath("$.questions.length()").value(3));
  mvc.perform(post("/api/reset-password").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\""+id+"\",\"answers\":[\"蓝星\",\"错\",\"错\"],\"new_password\":\"newpassword123\"}")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/reset-password").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\""+id+"\",\"answers\":[\"蓝星\",\"面条\",\"错\"],\"new_password\":\"newpassword123\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/me").cookie(original)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\""+id+"\",\"password\":\"newpassword123\"}")).andExpect(status().isOk());
 }
 @Test void invitationRevealsContactsOnlyAfterAcceptance()throws Exception{
  register("owner@test.example");register("guest@test.example");Cookie owner=login("owner@test.example"),guest=login("guest@test.example");
  mvc.perform(patch("/api/me").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"主人\",\"contact_email\":\"owner@test.example\",\"personality\":[],\"interests\":[],\"skills\":[],\"wants\":[]}")).andExpect(status().isOk());
  mvc.perform(patch("/api/me").cookie(guest).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"访客\",\"contact_email\":\"guest@test.example\",\"personality\":[],\"interests\":[],\"skills\":[],\"wants\":[]}")).andExpect(status().isOk());
  long ownerId=data(mvc.perform(get("/api/me").cookie(owner)).andReturn()).get("user").get("id").asLong();
  long guestId=data(mvc.perform(get("/api/me").cookie(guest)).andReturn()).get("user").get("id").asLong();
  MvcResult pub=mvc.perform(get("/api/users/"+guestId).cookie(owner)).andExpect(status().isOk()).andReturn();if(data(pub).get("user").has("contact_email"))throw new AssertionError("public contact leak");
  MvcResult need=mvc.perform(post("/api/needs").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"学习\",\"title\":\"一起自习\",\"description\":\"图书馆\",\"tags\":[],\"slots\":[{\"day\":\"周六\",\"start\":\"14:00\",\"end\":\"16:00\"}]}")).andExpect(status().isCreated()).andReturn();long needId=data(need).get("id").asLong();
  MvcResult inv=mvc.perform(post("/api/invitations").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"need_id\":"+needId+",\"receiver_id\":"+guestId+"}")).andExpect(status().isCreated()).andReturn();long invId=data(inv).get("id").asLong();
  mvc.perform(get("/api/contacts/"+invId).cookie(owner)).andExpect(status().isForbidden());
  mvc.perform(patch("/api/invitations/"+invId).cookie(guest).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"accepted\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/contacts/"+invId).cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.contacts[0].email").value("owner@test.example"));
  mvc.perform(patch("/api/invitations/"+invId).cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"withdrawn\"}")).andExpect(status().isConflict());
 }
}
