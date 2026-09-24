package io.servicecomb.poc.demo.seckill.web;

import java.util.Collection;
import java.util.Date;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import io.servicecomb.poc.demo.seckill.SecKillQueryService;
import io.servicecomb.poc.demo.seckill.dto.CouponInfo;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;

/**
 * 按券编号增量拉取新券的 HTTP 入口。数据来自 Redis 读模型，不读 Elasticsearch，也不写任何存储。
 * <p>
 * {@code @RestController} 表示返回值直接成为 JSON 响应体。{@code @RequestMapping("/sync")} 是路径前缀，
 * 方法上的 {@code GET /{latestId}} 拼起来是 {@code GET /sync/{latestId}}。
 * 字段上的 {@code @Autowired} 是字段注入：Spring 创建本类后，把 {@link SecKillQueryService} 填进来。
 * <p>
 * 页面用上次看到的最大编号来拉「之后的券」。刚抢成功的券要等 Event 服务投影完成才会出现。
 */
@RestController
@RequestMapping("/sync")
public class SecKillSyncRestController {
  private static final Logger logger = LoggerFactory.getLogger(SecKillSyncRestController.class);

  @Autowired
  private SecKillQueryService queryService;

  /**
   * 返回编号大于 {@code latestId} 的券。
   * <p>
   * {@code @PathVariable("latestId")} 把 URL 路径段填进参数。参数是字符串，方法里再解析成整数。
   *
   * @param latestId 客户端已经见过的最大券编号，必须能解析成整数
   * @return Redis 读模型中更新的券，转成 {@link CouponInfo} 列表
   */
  @RequestMapping(method = RequestMethod.GET, value = "/{latestId}")
  public Collection<CouponInfo> syncCoupon(@PathVariable("latestId") String latestId) {
    logger.info("Sync coupons from Id = {}", latestId);
    Collection<CouponEntity<String>> coupons = queryService.getLatestCoupons(Integer.parseInt(latestId));
    return coupons.stream()
        .map(coupon -> new CouponInfo(coupon.getId(), coupon.getCustomerId(), coupon.getPromotionId(),
            new Date(coupon.getTime()), coupon.getDiscount()))
        .collect(Collectors.toList());
  }
}
