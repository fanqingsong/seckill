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

package io.servicecomb.poc.demo.seckill.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.servicecomb.poc.demo.seckill.SecKillCommandService;
import io.servicecomb.poc.demo.seckill.SecKillGrabResult;
import io.servicecomb.poc.demo.seckill.dto.CouponDto;

/**
 * 抢券的 HTTP 入口。
 * <p>
 * {@code @RestController} 表示本类接收 HTTP，方法的返回值直接写成响应体，而不是跳转页面。
 * {@code @RequestMapping("/command/coupons")} 加上方法上的 {@code POST "/"}，完整路径是
 * {@code POST /command/coupons/}。浏览器经 nginx 把 {@code /command} 转到 Gateway，Gateway 再转到本服务。
 * <p>
 * 构造器上的 {@code @Autowired} 是构造器注入：Spring 把配置类里那份活动 Map 传进来。
 * 活动还没到 {@code publishTime} 时，Map 里没有这个编号。
 * <p>
 * 返回 200 和正文 {@code Request accepted} 时，只说明 Redis Lua 扣减成功。
 * PostgreSQL 还没有这张券的事件行，Kafka 也还没发。查询页暂时看不到券是正常延迟。
 */
@RestController
@RequestMapping("/command/coupons")
public class SecKillCommandRestController {

  private static final Logger logger = LoggerFactory.getLogger(SecKillCommandRestController.class);

  private final Map<String, SecKillCommandService<String>> commandServices;

  /**
   * @param commandServices 键为 promotionId。到点后由引导器放入，本类只读
   */
  @Autowired
  public SecKillCommandRestController(Map<String, SecKillCommandService<String>> commandServices) {
    this.commandServices = commandServices;
  }

  /**
   * 接受一次抢券。成功返回 200；失败抛出 {@link ResponseStatusException}，由 Spring 转成 4xx。
   *
   * @param couponDto 请求 JSON，需要 promotionId 和 customerId。{@code @RequestBody} 表示 Spring 用请求体反序列化它
   * @return 仅 Redis 扣减成功时返回，正文固定为 {@code Request accepted}
   */
  @RequestMapping(method = RequestMethod.POST, value = "/")
  public ResponseEntity<String> seckill(
      @RequestBody CouponDto couponDto) {
    if (isValidCoupon(couponDto)) {
      if (commandServices.containsKey(couponDto.getPromotionId())) {
        SecKillGrabResult result = commandServices.get(couponDto.getPromotionId())
            .addCouponTo(couponDto.getCustomerId());
        logger.info("SecKill from = {}, result = {}", couponDto.getCustomerId(), result);
        if (result == SecKillGrabResult.Success) {
          // Redis 已扣减。返回 200 并不表示 PostgreSQL 已有 CouponGrabbedEvent。
          return new ResponseEntity<>("Request accepted", HttpStatus.OK);
        } else if (result == SecKillGrabResult.Failed) {
          // 活动尚未开始、已经结束，或库存不足。Lua 没有放入新的抢券令牌。
          throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
              "Request rejected due to coupon out of stock");
        } else {
          // 重复顾客：同一活动里这个顾客已经抢过。
          throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
              "Request rejected duplicate order");
        }
      } else {
        // 活动尚未开始（还没放进内存表），或编号根本不存在。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            String.format("Invalid promotion {promotion=%s}", couponDto.getPromotionId()));
      }
    } else {
      // promotionId 或 customerId 为空，请求不能对应到一场活动和一名顾客。
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Invalid coupon {promotionId is null or customerId is null}");
    }
  }

  /**
   * 两个编号都有值才继续抢券。
   *
   * @param couponDto 控制器已经解析好的请求体
   * @return customerId 和 promotionId 都不是 null 时为 true
   */
  private boolean isValidCoupon(@RequestBody CouponDto couponDto) {
    return couponDto.getCustomerId() != null && couponDto.getPromotionId() != null;
  }
}
