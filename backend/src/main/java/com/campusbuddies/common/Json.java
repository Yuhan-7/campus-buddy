package com.campusbuddies.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/** 标签、时间段等列表字段以 JSON 文本保存在数据库中。 */
@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String encode(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 解析失败时返回空列表，避免个别脏数据导致整页无法显示。 */
  public List<Object> decode(Object text) {
    if (text == null) return List.of();
    try {
      List<Object> value = mapper.readValue(String.valueOf(text), new TypeReference<List<Object>>() {});
      return value == null ? List.of() : value;
    } catch (Exception e) {
      return List.of();
    }
  }
}
