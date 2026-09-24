/*
 *   Copyright 2017 Huawei Technologies Co., Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.servicecomb.poc.demo.seckill.json;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.servicecomb.poc.demo.seckill.SecKillException;
import io.servicecomb.poc.demo.seckill.Format;
import java.io.IOException;

/**
 * 用 Jackson 把活动、券和消息变成 JSON 字符串，或从字符串还原。
 * <p>
 * {@link io.servicecomb.poc.demo.seckill.SecKillJacksonConfig} 把它注册成 {@link Format}。
 * 事件正文、outbox payload 都经过它。它不连接 PostgreSQL、Redis、Kafka 或 Elasticsearch。
 * 失败时抛出 {@link SecKillException}，这是运行时异常，调用方不用在方法签名上写 {@code throws}。
 * <p>
 * {@code static import} 把 {@code ANY}、{@code NONE} 这两个可见性常量直接拿来用，不用写全名。
 */
public class JacksonGeneralFormat implements Format {

  /**
   * Jackson 的转换器。一个实例可以反复用。构造器里改了可见性：只看字段，不看 getter/setter。
   */
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 让 JSON 按字段读写。活动、券、消息上的 getter 因此不会决定 JSON 的形状。
   * {@code withFieldVisibility(ANY)} 表示任何可见性的字段都参与；getter、setter、构造器设为
   * {@code NONE}，避免同一属性被读写两次。不访问数据库。
   */
  public JacksonGeneralFormat() {
    objectMapper.setVisibility(
        objectMapper.getSerializationConfig()
            .getDefaultVisibilityChecker()
            .withFieldVisibility(ANY)
            .withGetterVisibility(NONE)
            .withSetterVisibility(NONE)
            .withCreatorVisibility(NONE));
  }

  /**
   * 把对象写成 JSON 字符串。Jackson 失败时包成 {@link SecKillException}。
   *
   * @param obj 活动、券或消息等
   * @return JSON 文本
   */
  @Override
  public String serialize(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (JsonProcessingException e) {
      throw new SecKillException("Json Exception", e);
    }
  }


  /**
   * 把 JSON 还原成调用方指定的类型。方法上的 {@code <T>} 是泛型方法：返回值和 {@code type} 是同一种类。
   *
   * @param content JSON 文本
   * @param type 要还原成的类，例如 {@code PromotionEntity.class}
   * @param <T> 目标类型
   * @return 还原后的对象。读失败时抛出 {@link SecKillException}
   */
  @Override
  public <T> T deserialize(String content, Class<T> type) {
    try {
      return objectMapper.readValue(content, type);
    } catch (IOException e) {
      throw new SecKillException("Json Exception", e);
    }
  }
}