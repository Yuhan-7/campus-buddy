package com.campusbuddies.admin;

import com.campusbuddies.auth.AccountService;
import com.campusbuddies.auth.PrivacyController;
import com.campusbuddies.common.Db;
import com.campusbuddies.common.Json;
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
 * 演示数据：仅当环境变量 DEMO_DATA=true 时写入，且只写一次（demo01 账号已存在则跳过）。
 * 所有演示账号邮箱均为 demoNN@buddy.demo，密码统一为 demo12345，联系方式均为虚构。
 * 正式使用前请把 DEMO_DATA 设回 false 并清空数据库。
 */
@Component
@Order(2)
public class DemoDataSeeder implements ApplicationRunner {
  public static final String PASSWORD = "demo12345";
  private final Db db;
  private final Json json;
  private final AccountService accounts;
  @Value("${DEMO_DATA:false}") private boolean enabled;

  public DemoDataSeeder(Db db, Json json, AccountService accounts) {
    this.db = db;
    this.json = json;
    this.accounts = accounts;
  }

  /** 昵称, 性别, 年级, 家乡, 介绍, 性格, 兴趣, 擅长, 想学 */
  private record Person(String name, String gender, String grade, String hometown, String bio,
                        List<String> personality, List<String> interests, List<String> skills, List<String> wants) {}

  /** 发布者序号(从 1 起), 类型, 标题, 说明, 标签, 星期, 开始, 结束, 几天前发布 */
  private record Need(int owner, String category, String title, String description, List<String> tags,
                      String day, String start, String end, int daysAgo) {}

  private static final List<Person> PEOPLE = List.of(
      new Person("小林", "男", "大三", "杭州", "计算机专业，周末喜欢打球和拍照。", List.of("开朗", "守时"), List.of("羽毛球", "摄影", "跑步"), List.of("Python", "Java"), List.of("吉他")),
      new Person("阿哲", "男", "大二", "成都", "校羽毛球队替补，想找固定球友。", List.of("外向", "靠谱"), List.of("羽毛球", "健身"), List.of("吉他"), List.of("Python")),
      new Person("晓雨", "女", "大一", "南京", "高数还行，英语口语比较弱。", List.of("认真", "安静"), List.of("自习", "阅读"), List.of("高数"), List.of("英语口语")),
      new Person("陈凯", "男", "大三", "广州", "高中在国外读过两年，可以陪练口语。", List.of("健谈"), List.of("英语", "电影"), List.of("英语口语"), List.of("高数")),
      new Person("林可", "女", "大二", "厦门", "胶片和数码都玩，喜欢清晨扫街。", List.of("随和", "有耐心"), List.of("摄影", "咖啡"), List.of("修图"), List.of("视频剪辑")),
      new Person("周舟", "男", "研一", "武汉", "弹了八年吉他，在学 Python 做数据分析。", List.of("幽默"), List.of("音乐", "吉他"), List.of("吉他"), List.of("Python")),
      new Person("小鹿", "女", "大三", "长沙", "每周三次夜跑，配速 6 分左右。", List.of("自律"), List.of("跑步", "健身"), List.of("跑步"), List.of("游泳")),
      new Person("王一", "男", "大三", "西安", "软件工程课程项目想找会前端的队友。", List.of("负责", "细心"), List.of("编程", "桌游"), List.of("Java", "Spring Boot"), List.of("Vue")),
      new Person("苏苏", "女", "大二", "重庆", "狼人杀爱好者，也玩剧本杀。", List.of("活泼"), List.of("桌游", "剧本杀"), List.of("Vue"), List.of("Java")),
      new Person("大鹏", "男", "大四", "济南", "双打缺人常年招募中。", List.of("热情"), List.of("羽毛球", "篮球"), List.of("羽毛球"), List.of("摄影")),
      new Person("安然", "女", "大四", "合肥", "在准备考研，想找能互相监督的研友。", List.of("坚持", "安静"), List.of("自习", "考研"), List.of("英语阅读"), List.of("高数")),
      new Person("何夕", "女", "研二", "苏州", "常去美术馆，也喜欢拍展。", List.of("文艺"), List.of("看展", "摄影"), List.of("策展写作"), List.of("修图")));

  private static final List<Need> NEEDS = List.of(
      new Need(1, "运动", "周六下午羽毛球搭子", "体育馆 3 号场，水平一般，开心就好。", List.of("羽毛球", "运动"), "周六", "14:00", "16:00", 1),
      new Need(2, "运动", "找羽毛球搭子，周末固定打球", "希望能长期一起打，球拍可以借。", List.of("羽毛球"), "周六", "13:00", "17:00", 2),
      new Need(10, "运动", "羽毛球双打缺两人", "已有两人，再来两位就开打。", List.of("羽毛球", "双打"), "周六", "15:00", "17:00", 0),
      new Need(3, "学习", "高数期末自习搭子", "主要刷题，互相讲题。", List.of("高数", "自习"), "周二", "19:00", "21:00", 3),
      new Need(4, "学习", "英语口语练习搭子", "每次聊一个话题，中途不说中文。", List.of("英语口语"), "周二", "19:30", "21:30", 4),
      new Need(11, "学习", "图书馆考研自习搭子", "早上占座，中午一起吃饭。", List.of("考研", "自习"), "周一", "08:00", "12:00", 2),
      new Need(5, "兴趣", "清晨摄影扫街搭子", "老城区一圈，大概两小时。", List.of("摄影"), "周日", "07:00", "09:00", 1),
      new Need(12, "兴趣", "周末看展搭子", "这周想去美术馆的新展。", List.of("看展", "摄影"), "周日", "14:00", "17:00", 3),
      new Need(9, "兴趣", "狼人杀桌游搭子", "宿舍楼下活动室，凑够 9 人开局。", List.of("桌游", "狼人杀"), "周六", "19:00", "22:00", 5),
      new Need(6, "技能互助", "互教吉他和 Python", "我教你吉他，你教我 Python。", List.of("吉他", "Python"), "周四", "20:00", "22:00", 2),
      new Need(7, "运动", "夜跑搭子，操场 5 公里", "配速 6 分左右，跑完拉伸。", List.of("跑步", "夜跑"), "周三", "20:00", "21:00", 0),
      new Need(8, "项目组队", "软件工程课程项目找前端队友", "后端我来，想找会 Vue 的同学。", List.of("Java", "Vue", "组队"), "周五", "14:00", "17:00", 4),
      new Need(1, "技能互助", "想学吉他，可以教 Python", "零基础，有一把旧吉他。", List.of("吉他", "Python"), "周四", "20:00", "21:30", 1));

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled || db.exists("SELECT id FROM users WHERE username=?", email(1))) return;

    List<Long> userIds = new ArrayList<>();
    for (int i = 0; i < PEOPLE.size(); i++) {
      Person p = PEOPLE.get(i);
      String name = accounts.nicknameTaken(p.name()) ? p.name() + (i + 1) : p.name();
      long id;
      try {
        id = accounts.create(email(i + 1), PASSWORD, name);
      } catch (DuplicateKeyException e) {
        return;
      }
      db.update("UPDATE users SET age=?,gender=?,grade=?,hometown=?,bio=?,personality=?,interests=?,skills=?,wants=?,contact_email=?,wechat=? WHERE id=?",
          String.valueOf(19 + i % 6), p.gender(), p.grade(), p.hometown(), p.bio(),
          json.encode(p.personality()), json.encode(p.interests()), json.encode(p.skills()), json.encode(p.wants()),
          email(i + 1), "demo_wx_" + (i + 1), id);
      db.update("INSERT INTO privacy_consents(user_id,policy_version) VALUES(?,?)", id, PrivacyController.POLICY_VERSION);
      userIds.add(id);
    }

    List<Long> needIds = new ArrayList<>();
    // 与数据库 CURRENT_TIMESTAMP 使用同一时区（容器默认 UTC），避免发布时间错位
    LocalDateTime now = LocalDateTime.now();
    for (int i = 0; i < NEEDS.size(); i++) {
      Need n = NEEDS.get(i);
      Map<String, String> slot = Map.of("day", n.day(), "start", n.start(), "end", n.end());
      needIds.add(db.insert("INSERT INTO needs(user_id,category,title,description,tags,slots,created_at) VALUES(?,?,?,?,?,?,?)",
          userIds.get(n.owner() - 1), n.category(), n.title(), n.description(), json.encode(n.tags()), json.encode(List.of(slot)),
          Timestamp.valueOf(now.minusDays(n.daysAgo()).minusHours(i))));
    }

    // 浏览记录：每条需求被若干演示用户在近几天浏览过，用于浏览量和热门主题
    Random random = new Random(20261111L);
    LocalDate today = LocalDate.now(AccountService.ZONE);
    for (int i = 0; i < needIds.size(); i++) {
      long owner = userIds.get(NEEDS.get(i).owner() - 1);
      int viewers = 2 + random.nextInt(8);
      for (int v = 0; v < viewers; v++) {
        long viewer = userIds.get(random.nextInt(userIds.size()));
        if (viewer == owner) continue;
        try {
          db.update("INSERT INTO need_views(need_id,user_id,view_date) VALUES(?,?,?)", needIds.get(i), viewer, Date.valueOf(today.minusDays(random.nextInt(6))));
        } catch (DuplicateKeyException ignored) {
          // 同一天重复浏览只计一次
        }
      }
    }

    // 邀请：给 demo01 留一条待处理的申请用于现场演示“同意”；另有几条历史记录供“匹配成功记录”展示。
    // demo01 与 demo02 之间不预置邀请，留给现场演示“邀请 → 同意 → 查看联系方式”。
    invite(needIds.get(0), userIds.get(9), userIds.get(0), "pending");    // 大鹏 申请 小林的羽毛球需求
    invite(needIds.get(3), userIds.get(3), userIds.get(2), "accepted");   // 陈凯 ↔ 晓雨
    invite(needIds.get(9), userIds.get(5), userIds.get(0), "accepted");   // 周舟 ↔ 小林（吉他/Python）
    invite(needIds.get(11), userIds.get(7), userIds.get(8), "pending");   // 王一 邀请 苏苏
    invite(needIds.get(6), userIds.get(4), userIds.get(11), "rejected");  // 林可 → 何夕
  }

  private void invite(long needId, long sender, long receiver, String status) {
    db.update("INSERT INTO invitations(need_id,sender_id,receiver_id,status) VALUES(?,?,?,?)", needId, sender, receiver, status);
  }

  public static String email(int n) {
    return String.format(Locale.ROOT, "demo%02d@buddy.demo", n);
  }
}
