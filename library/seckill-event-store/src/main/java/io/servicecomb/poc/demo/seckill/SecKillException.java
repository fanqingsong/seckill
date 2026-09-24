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

package io.servicecomb.poc.demo.seckill;

/**
 * 本库在 JSON 转换失败时抛出的运行时异常。
 * <p>
 * {@link io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat} 捕获 Jackson 的受检异常后包成它。
 * {@code extends RuntimeException} 表示调用方可以不声明 {@code throws}。它不写 PostgreSQL、
 * Redis、Kafka 或 Elasticsearch，只把原因字符串和原始异常交给父类。
 */
public class SecKillException extends RuntimeException {

  /**
   * 保留一句说明和底层异常，便于日志看到是 JSON 失败而不是业务拒绝。
   *
   * @param cause 说明文字，现有调用传入 {@code "Json Exception"}
   * @param e Jackson 抛出的原始异常
   */
  public SecKillException(String cause, Throwable e) {
    super(cause, e);
  }
}