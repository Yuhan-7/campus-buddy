package com.campusbuddies.admin;

import com.campusbuddies.auth.AccountService;
import com.campusbuddies.auth.PrivacyController;
import com.campusbuddies.common.Db;
import com.campusbuddies.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 演示数据：DEMO_DATA=true 时在首次启动写入，只写一次（已存在 @buddy.demo 账号则跳过）。
 * <ul>
 *   <li>测试账号 test@buddy.demo / test12345：资料、4 条需求、收发的各种状态邀请、站内信、密保问题齐全</li>
 *   <li>30 个演示用户 demo01~demo30@buddy.demo / demo12345，36 条需求，近 7 天浏览记录，约 30 条邀请</li>
 *   <li>1 个被停用账号、1 条被管理员撤下的帖子，供管理端演示</li>
 * </ul>
 * 姓名、联系方式均为虚构。正式使用前请把 DEMO_DATA 设为 false 并清空数据库。
 */
@Component
@Order(2)
public class DemoDataSeeder implements ApplicationRunner {
  public static final String TEST_EMAIL = "test@buddy.demo";
  public static final String TEST_PASSWORD = "test12345";
  public static final String PASSWORD = "demo12345";
  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final Db db;
  private final Json json;
  private final AccountService accounts;
  @Value("${DEMO_DATA:false}") private boolean enabled;
  private final Random random = new Random(20261111L);

  public DemoDataSeeder(Db db, Json json, AccountService accounts) {
    this.db = db;
    this.json = json;
    this.accounts = accounts;
  }

  /** 类型, 标题, 说明, 标签, 星期, 开始, 结束 */
  private record Need(String category, String title, String description, List<String> tags, String day, String start, String end) {}

  private static final List<String> NAMES = List.of("阿哲", "晓雨", "陈凯", "林可", "周舟", "小鹿", "王一", "苏苏", "大鹏", "安然",
      "何夕", "子墨", "乐乐", "一帆", "思源", "若曦", "浩然", "嘉怡", "星河", "沐阳",
      "小满", "欣怡", "宇航", "梓萱", "天佑", "雨桐", "明轩", "诗涵", "俊杰", "可可");
  private static final List<String> GRADES = List.of("大一", "大二", "大三", "大四", "研一", "研二");
  private static final List<String> HOMETOWNS = List.of("杭州", "成都", "南京", "广州", "厦门", "武汉", "长沙", "西安", "重庆", "济南", "合肥", "苏州", "青岛", "昆明", "郑州");
  private static final List<String> PERSONALITY = List.of("开朗", "安静", "靠谱", "幽默", "细心", "外向", "自律", "随和", "守时", "热情");
  private static final List<String> INTERESTS = List.of("羽毛球", "篮球", "跑步", "健身", "游泳", "摄影", "看展", "桌游", "剧本杀", "吉他", "自习", "考研", "阅读", "电影", "咖啡", "编程", "英语");
  private static final List<String> SKILLS = List.of("Python", "Java", "Vue", "高数", "英语口语", "吉他", "摄影", "修图", "视频剪辑", "羽毛球", "游泳", "PPT设计");

  private static final List<Need> NEEDS = List.of(
      new Need("运动", "周六羽毛球双打缺两人", "体育馆 3 号场，水平中等，来了就开打。", List.of("羽毛球", "双打"), "周六", "15:00", "17:00"),
      new Need("运动", "找羽毛球搭子，周末固定打球", "球拍可以借，开心为主。", List.of("羽毛球"), "周六", "13:00", "17:00"),
      new Need("运动", "羽毛球新手练习搭子", "刚开始学，想找耐心的同学一起练。", List.of("羽毛球", "新手"), "周日", "10:00", "12:00"),
      new Need("运动", "工作日晚上打羽毛球", "下课后打一个半小时。", List.of("羽毛球"), "周三", "19:00", "20:30"),
      new Need("运动", "夜跑搭子，操场 5 公里", "配速 6 分左右，跑完一起拉伸。", List.of("跑步", "夜跑"), "周三", "20:00", "21:00"),
      new Need("运动", "晨跑打卡搭子", "每天早上六点半，互相叫醒。", List.of("跑步", "晨跑"), "周一", "06:30", "07:30"),
      new Need("运动", "周末半马训练", "准备明年春天的半程马拉松。", List.of("跑步", "马拉松"), "周日", "07:00", "09:00"),
      new Need("运动", "篮球 3v3 缺一人", "东区篮球场，打半场。", List.of("篮球"), "周五", "16:00", "18:00"),
      new Need("运动", "周末篮球约球", "水平一般，主要锻炼身体。", List.of("篮球"), "周六", "09:00", "11:00"),
      new Need("运动", "健身房带练搭子", "想找有经验的同学带一下动作。", List.of("健身"), "周二", "18:00", "19:30"),
      new Need("运动", "一起去校游泳馆", "自由泳在练换气。", List.of("游泳"), "周四", "17:00", "18:30"),
      new Need("学习", "高数期末自习搭子", "主要刷题，互相讲题。", List.of("高数", "自习"), "周二", "19:00", "21:00"),
      new Need("学习", "图书馆考研自习搭子", "早上占座，中午一起吃饭。", List.of("考研", "自习"), "周一", "08:00", "12:00"),
      new Need("学习", "考研英语互相监督", "每天一篇阅读，晚上对答案。", List.of("考研", "英语"), "周三", "19:00", "21:00"),
      new Need("学习", "考研数学一起刷题", "在刷习题册，互相讲错题。", List.of("考研", "高数"), "周五", "08:00", "11:00"),
      new Need("学习", "英语口语练习搭子", "每次聊一个话题，中途不说中文。", List.of("英语口语"), "周二", "19:30", "21:30"),
      new Need("学习", "雅思口语陪练", "目标 6.5，想找人模拟考试。", List.of("英语口语", "雅思"), "周四", "19:00", "20:30"),
      new Need("学习", "周末图书馆自习", "安静学习，休息时一起吃饭。", List.of("自习"), "周六", "09:00", "12:00"),
      new Need("学习", "线性代数期中复习", "一起整理知识点。", List.of("线代", "自习"), "周日", "14:00", "17:00"),
      new Need("学习", "四六级备考搭子", "每天背单词打卡。", List.of("四六级", "英语"), "周一", "19:00", "20:00"),
      new Need("兴趣", "清晨摄影扫街搭子", "老城区一圈，大概两小时。", List.of("摄影"), "周日", "07:00", "09:00"),
      new Need("兴趣", "周末看展搭子", "这周想去美术馆的新展。", List.of("看展", "摄影"), "周日", "14:00", "17:00"),
      new Need("兴趣", "狼人杀桌游局", "活动室，凑够 9 人开局。", List.of("桌游", "狼人杀"), "周六", "19:00", "22:00"),
      new Need("兴趣", "剧本杀车队招人", "还差两位，新手友好。", List.of("剧本杀"), "周五", "19:00", "23:00"),
      new Need("兴趣", "一起去电影院看新片", "周末下午场，看完一起吃饭。", List.of("电影"), "周六", "14:00", "17:00"),
      new Need("兴趣", "咖啡探店搭子", "每周一家，学校附近。", List.of("咖啡", "探店"), "周日", "10:00", "12:00"),
      new Need("兴趣", "读书会：每月一本", "这个月读一本推理小说。", List.of("阅读", "读书会"), "周四", "19:00", "21:00"),
      new Need("技能互助", "互教吉他和 Python", "我教你吉他，你教我 Python。", List.of("吉他", "Python"), "周四", "20:00", "22:00"),
      new Need("技能互助", "PPT 设计互助", "帮你改 PPT，你教我剪视频。", List.of("PPT设计", "视频剪辑"), "周三", "19:00", "21:00"),
      new Need("技能互助", "修图入门交流", "会一点调色，想一起进步。", List.of("修图", "摄影"), "周日", "15:00", "17:00"),
      new Need("技能互助", "Java 期末答疑", "可以讲集合和多线程。", List.of("Java", "编程"), "周二", "20:00", "21:30"),
      new Need("项目组队", "软件工程课程项目找前端队友", "后端我来，想找会 Vue 的同学。", List.of("Java", "Vue", "组队"), "周五", "14:00", "17:00"),
      new Need("项目组队", "数学建模组队", "缺一名擅长编程的队友。", List.of("数学建模", "Python", "组队"), "周六", "09:00", "12:00"),
      new Need("项目组队", "大创项目招募", "做校园 App，缺 UI 设计。", List.of("大创", "组队"), "周三", "14:00", "16:00"),
      new Need("项目组队", "互联网+ 比赛找队友", "已有想法，缺做路演 PPT 的同学。", List.of("比赛", "组队"), "周四", "14:00", "17:00"),
      new Need("运动", "周末骑行去湖边", "单程 15 公里，中途休息。", List.of("骑行"), "周日", "08:00", "11:00"));

  /** 测试账号自己的 4 条需求（最后一条为已关闭） */
  private static final List<Need> TEST_NEEDS = List.of(
      new Need("运动", "周六下午羽毛球搭子", "体育馆 2 号场，水平一般，开心就好。", List.of("羽毛球", "运动"), "周六", "14:00", "16:00"),
      new Need("学习", "周二晚上英语口语练习", "想提高口语，每次聊半小时以上。", List.of("英语口语"), "周二", "19:00", "21:00"),
      new Need("技能互助", "想学吉他，可以教 Python", "零基础，有一把旧吉他。", List.of("吉他", "Python"), "周四", "20:00", "21:30"),
      new Need("兴趣", "周日清晨扫街拍照", "已经找到搭子，先关闭。", List.of("摄影"), "周日", "07:00", "09:00"));

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) return;
    if (db.exists("SELECT id FROM users WHERE username LIKE ?", "%@buddy.demo")) {
      log.info("演示数据已存在，跳过写入。如需重新生成，请先执行 docker compose down -v");
      return;
    }
    LocalDateTime now = LocalDateTime.now();

    // ---------- 用户 ----------
    long testId = createUser(TEST_EMAIL, TEST_PASSWORD, "测试同学", "大三", "杭州", "男", "21",
        "软件工程专业，平时喜欢打羽毛球和拍照，最近想学吉他。", List.of("开朗", "守时"),
        List.of("羽毛球", "摄影", "跑步"), List.of("Python", "Java"), List.of("吉他", "英语口语"), "test_wx_demo", now.minusDays(20));
    setSecurityQuestions(testId);

    List<Long> users = new ArrayList<>();
    for (int i = 0; i < NAMES.size(); i++) {
      List<String> interests = pick(INTERESTS, 3);
      List<String> skills = pick(SKILLS, 2);
      List<String> wants = pick(SKILLS.stream().filter(s -> !skills.contains(s)).toList(), 1);
      String grade = GRADES.get(i % GRADES.size());
      String bio = grade + "，喜欢" + interests.get(0) + "和" + interests.get(1) + "，擅长" + skills.get(0) + "，想学" + wants.get(0) + "。";
      users.add(createUser(String.format(Locale.ROOT, "demo%02d@buddy.demo", i + 1), PASSWORD, NAMES.get(i), grade,
          HOMETOWNS.get(i % HOMETOWNS.size()), i % 2 == 0 ? "男" : "女", String.valueOf(18 + i % 7), bio,
          pick(PERSONALITY, 2), interests, skills, wants, "demo_wx_" + (i + 1), now.minusDays(1 + random.nextInt(28))));
    }

    // ---------- 需求 ----------
    List<Long> needIds = new ArrayList<>(), needOwners = new ArrayList<>();
    for (int i = 0; i < NEEDS.size(); i++) {
      long owner = users.get(i % 29); // 第 30 个用户留作“被停用账号”
      needIds.add(createNeed(owner, NEEDS.get(i), "open", now.minusHours(2 + random.nextInt(6 * 24))));
      needOwners.add(owner);
    }
    List<Long> testNeeds = new ArrayList<>();
    for (int i = 0; i < TEST_NEEDS.size(); i++) {
      testNeeds.add(createNeed(testId, TEST_NEEDS.get(i), i == 3 ? "closed" : "open", now.minusDays(4 - i).minusHours(3)));
    }
    long spam = createNeed(users.get(28), new Need("技能互助", "有偿代写课程作业", "价格私聊。", List.of("代写"), "周一", "10:00", "12:00"), "removed", now.minusDays(2));
    db.update("INSERT INTO disabled_users(user_id) VALUES(?)", users.get(29));

    // ---------- 浏览记录（用于浏览量与热门主题） ----------
    List<Long> everyone = new ArrayList<>(users.subList(0, 29));
    everyone.add(testId);
    List<Long> allNeeds = new ArrayList<>(needIds);
    allNeeds.addAll(testNeeds);
    for (long needId : allNeeds) {
      int views = 4 + random.nextInt(26);
      for (int v = 0; v < views; v++) {
        try {
          db.update("INSERT INTO need_views(need_id,user_id,view_date) VALUES(?,?,?)", needId,
              everyone.get(random.nextInt(everyone.size())), Date.valueOf(LocalDate.now().minusDays(random.nextInt(6))));
        } catch (DuplicateKeyException ignored) {
          // 同一账号同一天只计一次
        }
      }
    }

    // ---------- 测试账号的邀请：收到 / 发出各种状态 ----------
    invite(testNeeds.get(0), needOwners.get(3), testId, "pending", now.minusHours(5));    // 收到：待处理
    invite(testNeeds.get(1), needOwners.get(15), testId, "pending", now.minusHours(9));   // 收到：待处理
    invite(testNeeds.get(2), needOwners.get(27), testId, "accepted", now.minusDays(2));   // 收到：已同意
    invite(testNeeds.get(0), needOwners.get(8), testId, "rejected", now.minusDays(3));    // 收到：已拒绝
    invite(needIds.get(12), testId, needOwners.get(12), "pending", now.minusHours(20));   // 发出：待处理
    invite(needIds.get(7), testId, needOwners.get(7), "accepted", now.minusDays(1));      // 发出：已同意
    invite(needIds.get(22), testId, needOwners.get(22), "withdrawn", now.minusDays(2));   // 发出：已撤回
    invite(testNeeds.get(3), needOwners.get(20), testId, "accepted", now.minusDays(1));   // 已关闭需求上的匹配

    // ---------- 其他用户之间的邀请 ----------
    String[] statuses = {"accepted", "accepted", "accepted", "pending", "pending", "rejected", "withdrawn"};
    for (int i = 0; i < needIds.size(); i++) {
      if (random.nextInt(10) < 2) continue;
      long owner = needOwners.get(i);
      long other = users.get(random.nextInt(29));
      if (other == owner) continue;
      boolean ownerSends = random.nextBoolean();
      invite(needIds.get(i), ownerSends ? owner : other, ownerSends ? other : owner, statuses[random.nextInt(statuses.length)], now.minusHours(1 + random.nextInt(5 * 24)));
    }

    // ---------- 管理员站内信（管理员由 .env 中 ADMIN_EMAIL 创建） ----------
    Map<String, Object> admin = db.row("SELECT user_id FROM admins ORDER BY user_id LIMIT 1");
    if (admin != null) {
      long adminId = Db.longValue(admin, "user_id");
      message(adminId, testId, testNeeds.get(0), "你好，帖子写了体育馆 2 号场，建议补充是否需要自带球拍，方便大家报名。");
      message(adminId, users.get(28), spam, "该帖子涉及有偿代写，违反平台规则，已撤下。");
      message(adminId, needOwners.get(23), needIds.get(23), "剧本杀活动请注意结束时间，避免影响宿舍门禁。");
    }
    log.info("演示数据写入完成：测试账号 {} / {}，演示用户 {} 个，需求 {} 条", TEST_EMAIL, TEST_PASSWORD, users.size(), allNeeds.size() + 1);
  }

  private long createUser(String email, String password, String name, String grade, String hometown, String gender, String age, String bio,
                          List<String> personality, List<String> interests, List<String> skills, List<String> wants, String wechat, LocalDateTime createdAt) {
    String nickname = accounts.nicknameTaken(name) ? accounts.newNickname() : name;
    long id = accounts.create(email, password, nickname);
    db.update("UPDATE users SET age=?,gender=?,grade=?,hometown=?,bio=?,personality=?,interests=?,skills=?,wants=?,contact_email=?,wechat=?,created_at=? WHERE id=?",
        age, gender, grade, hometown, bio, json.encode(personality), json.encode(interests), json.encode(skills), json.encode(wants),
        email, wechat, Timestamp.valueOf(createdAt), id);
    db.update("INSERT INTO privacy_consents(user_id,policy_version) VALUES(?,?)", id, PrivacyController.POLICY_VERSION);
    return id;
  }

  /** 测试账号的密保：答案分别为 杭州 / 小橘 / 羽毛球，可用于演示“忘记密码” */
  private void setSecurityQuestions(long userId) {
    String[][] qa = {{"你的家乡是哪里？", "杭州"}, {"你养的宠物叫什么名字？", "小橘"}, {"你最喜欢的运动是什么？", "羽毛球"}};
    for (int i = 0; i < qa.length; i++) {
      db.update("INSERT INTO security_questions(user_id,question_no,question,answer_hash) VALUES(?,?,?,?)", userId, i + 1, qa[i][0], accounts.encode(qa[i][1]));
    }
  }

  private long createNeed(long owner, Need n, String status, LocalDateTime createdAt) {
    Map<String, String> slot = Map.of("day", n.day(), "start", n.start(), "end", n.end());
    return db.insert("INSERT INTO needs(user_id,category,title,description,tags,slots,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
        owner, n.category(), n.title(), n.description(), json.encode(n.tags()), json.encode(List.of(slot)), status, Timestamp.valueOf(createdAt));
  }

  private static final List<String> REJECT_REASONS = List.of("时间对不上，下次再约", "已经找到搭子了，谢谢", "这周比较忙，暂时不参加", "");

  private void invite(long needId, long sender, long receiver, String status, LocalDateTime at) {
    if (sender == receiver) return;
    String reason = "rejected".equals(status) ? REJECT_REASONS.get(random.nextInt(REJECT_REASONS.size())) : "";
    db.update("INSERT INTO invitations(need_id,sender_id,receiver_id,status,reason,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
        needId, sender, receiver, status, reason, Timestamp.valueOf(at), Timestamp.valueOf(at));
  }

  private void message(long sender, long receiver, long needId, String body) {
    db.update("INSERT INTO admin_messages(sender_id,receiver_id,need_id,body) VALUES(?,?,?,?)", sender, receiver, needId, body);
  }

  private List<String> pick(List<String> pool, int n) {
    List<String> copy = new ArrayList<>(pool);
    Collections.shuffle(copy, random);
    return new ArrayList<>(copy.subList(0, Math.min(n, copy.size())));
  }
}
