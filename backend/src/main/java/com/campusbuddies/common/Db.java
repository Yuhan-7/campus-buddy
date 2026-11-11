package com.campusbuddies.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** JdbcTemplate 的薄封装，减少各模块中重复的查询样板代码。 */
@Component
public class Db {
  private final JdbcTemplate jdbc;

  public Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.queryForList(sql, args);
  }

  /** 返回第一行，没有结果时返回 null。 */
  public Map<String, Object> row(String sql, Object... args) {
    List<Map<String, Object>> found = rows(sql, args);
    return found.isEmpty() ? null : found.get(0);
  }

  /** 返回第一行，没有结果时抛出 404。 */
  public Map<String, Object> must(String sql, String notFoundMessage, Object... args) {
    Map<String, Object> found = row(sql, args);
    if (found == null) throw ApiException.missing(notFoundMessage);
    return found;
  }

  public boolean exists(String sql, Object... args) {
    return row(sql, args) != null;
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  public void execute(String sql) {
    jdbc.execute(sql);
  }

  /** 执行 INSERT 并返回自增主键。 */
  public long insert(String sql, Object... args) {
    GeneratedKeyHolder key = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      var statement = connection.prepareStatement(sql, new String[] {"id"});
      for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
      return statement;
    }, key);
    return key.getKey().longValue();
  }

  public static long id(Map<String, Object> row) {
    return longValue(row, "id");
  }

  public static long longValue(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).longValue();
  }
}
