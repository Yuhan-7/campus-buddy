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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 演示数据开关打开时：测试账号可登录，广场、热门主题、需求、邀请、推荐、站内信均有数据。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=always", "DEMO_DATA=true", "ADMIN_EMAIL=admin@demo.example", "ADMIN_PASSWORD=adminpassword123"})
@AutoConfigureMockMvc
class DemoDataTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private JsonNode get(String url, Cookie cookie) throws Exception {
    var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
    if (cookie != null) request = request.cookie(cookie);
    MvcResult r = mvc.perform(request).andExpect(status().isOk()).andReturn();
    return json.readTree(r.getResponse().getContentAsString());
  }

  @Test
  @DisplayName("演示数据：测试账号的需求、收发邀请、推荐、站内信与广场热门主题均有数据")
  void demoDataUsable() throws Exception {
    assertTrue(get("/api/needs", null).get("needs").size() >= 20, "广场应有演示需求");
    assertTrue(get("/api/trending", null).get("topics").size() >= 5, "热门主题应有数据");

    MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"account\":\"" + DemoDataSeeder.TEST_EMAIL + "\",\"password\":\"" + DemoDataSeeder.TEST_PASSWORD + "\"}")).andExpect(status().isOk()).andReturn();
    Cookie cookie = new Cookie("buddy_session", login.getResponse().getHeader("Set-Cookie").split("=")[1].split(";")[0]);

    JsonNode mine = get("/api/my-needs", cookie).get("needs");
    assertTrue(mine.size() == 4, "测试账号应有 4 条需求");
    assertTrue(get("/api/recommend/" + mine.get(mine.size() - 1).get("id").asLong(), cookie).get("matches").size() >= 2, "羽毛球需求应能推荐出候选人");
    assertTrue(get("/api/invitations", cookie).get("invitations").size() >= 7, "测试账号应有收发邀请记录");
    assertTrue(get("/api/messages", cookie).get("messages").size() >= 1, "测试账号应收到站内信");
  }
}
