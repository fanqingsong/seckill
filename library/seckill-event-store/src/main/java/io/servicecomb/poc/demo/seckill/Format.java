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

/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ Format.java                                          │
 * │ 链路：JSON 抽象                                      │
 * └──────────────────────────────────────────────────────┘
 *
 *   事件转换与 outbox 序列化
 *      │
 *      ▼
 *   【本文件】serialize / deserialize 约定
 *      │
 *      ▼
 *   JacksonGeneralFormat 负责实现
 *
 * 一句话：接口只约定互转，本身不访问数据库。
 */

package io.servicecomb.poc.demo.seckill;

/**
 * 把 Java 对象和 JSON 字符串互转的抽象。事件正文和 outbox 都依赖它，具体实现是 Jackson。
 * <p>
 * {@link SecKillJacksonConfig} 注册唯一实现。Command、Persist 用它序列化消息；Event 服务用它
 * 反序列化 Kafka 正文。接口本身不访问 PostgreSQL、Redis、Kafka 或 Elasticsearch。
 * 方法上的 {@code <T>} 是泛型方法：{@code type} 参数决定还原出来的类型。
 */
public interface Format {

  /**
   * 把对象写成 JSON。
   *
   * @param obj 活动、券或消息
   * @return JSON 文本。实现失败时抛出 {@link SecKillException}
   */
  String serialize(Object obj);

  /**
   * 把 JSON 还原成指定类型。
   *
   * @param content JSON 文本
   * @param type 目标类
   * @param <T> 目标类型，与 {@code type} 一致
   * @return 还原后的对象
   */
  <T> T deserialize(String content,Class<T> type);
}
