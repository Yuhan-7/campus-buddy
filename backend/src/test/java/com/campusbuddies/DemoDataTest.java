package com.campusbuddies;

import com.campusbuddies.admin.DemoDataSeeder;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 演示数据开关打开时：演示账号可登录，广场有数据，demo01 的羽毛球需求能推荐出候选人。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=always", "DEMO_DATA=true"})
@AutoConfigureMockMvc
class DemoDataTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  @DisplayName("演示数据：可登录、广场有需求、推荐不为空、有待处理邀请")
  void demoDataUsable() throws Exception {
    MvcResult plaza = mvc.perform(get("/api/needs")).andExpect(status().isOk()).andReturn();
    assertTrue(json.readTree(plaza.getResponse().getContentAsString()).get("needs").size() >= 10, "广场应有演示需求");

    MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"account\":\"" + DemoDataSeeder.email(1) + "\",\"password\":\"" + DemoDataSeeder.PASSWORD + "\"}")).andExpect(status().isOk()).andReturn();
    Cookie cookie = new Cookie("buddy_session", login.getResponse().getHeader("Set-Cookie").split("=")[1].split(";")[0]);

    JsonNode mine = json.readTree(mvc.perform(get("/api/my-needs").cookie(cookie)).andReturn().getResponse().getContentAsString()).get("needs");
    long badminton = -1;
    for (int i = 0; i < mine.size(); i++) if (mine.get(i).get("title").asText().contains("羽毛球")) badminton = mine.get(i).get("id").asLong();
    assertTrue(badminton > 0, "demo01 应有羽毛球需求");
    JsonNode matches = json.readTree(mvc.perform(get("/api/recommend/" + badminton).cookie(cookie)).andReturn().getResponse().getContentAsString()).get("matches");
    assertTrue(matches.size() >= 2, "应推荐出阿哲和大鹏");

    JsonNode invitations = json.readTree(mvc.perform(get("/api/invitations").cookie(cookie)).andReturn().getResponse().getContentAsString()).get("invitations");
    assertTrue(invitations.size() >= 2, "demo01 应有一条待处理申请和一条已同意记录");
  }
}
