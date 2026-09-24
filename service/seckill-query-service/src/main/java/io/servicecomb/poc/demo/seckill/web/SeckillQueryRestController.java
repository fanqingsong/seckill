/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ SeckillQueryRestController.java                 │
 * │ 链路：查询 · Query HTTP                         │
 * └─────────────────────────────────────────────────┘
 *
 * Gateway /query → 本服务 8083
 * │
 * ▼
 * 【本文件】查询页入口
 * │
 * ├── GET /promotions、/coupons/{id} ──▶ Redis
 * └── GET /coupons/search ──▶ Elasticsearch
 *
 * 一句话：抢券 HTTP 成功只表示库存已扣，列表要等投影后才有券。
 */

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.servicecomb.poc.demo.seckill.SecKillQueryService;
import io.servicecomb.poc.demo.seckill.dto.CouponInfo;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;

/**
 * 查询页的 HTTP 入口。进行中的活动和「我的券」读 Redis；只有搜索路径读 Elasticsearch。
 * <p>
 * {@code @RestController} 表示返回值直接写成 JSON。{@code @RequestMapping("/query")} 是前缀。
 * 浏览器经 nginx 把 {@code /query} 转到 Gateway，Gateway 再转到本服务（8083）。
 * 字段上的 {@code @Autowired} 是字段注入：Spring 把 {@link SecKillQueryService} 填进 {@code queryService}。
 * <p>
 * 抢券接口返回成功，只说明 Command 侧 Redis 库存已扣。本类读的是另一份读模型，投影未完成时列表里还没有那张券。
 */
@RestController
@RequestMapping("/query")
public class SeckillQueryRestController {

  private static final Logger logger = LoggerFactory.getLogger(SeckillQueryRestController.class);

  @Autowired
  private SecKillQueryService queryService;

  /**
   * 查询一名顾客的券。完整路径是 {@code GET /query/coupons/{customerId}}，只读 Redis。
   * <p>
   * {@code @PathVariable("customerId")} 把路径中的顾客编号填进参数。
   *
   * @param customerId 顾客编号
   * @return 读模型里已有的券；尚未投影的抢券结果不在列表中
   */
  @RequestMapping(method = RequestMethod.GET, value = "/coupons/{customerId}")
  public Collection<CouponInfo> querySuccess(@PathVariable("customerId") String customerId) {
    logger.info("Query customer id = {} coupons", customerId);
    return toInfo(queryService.getCustomerCoupons(customerId));
  }

  /**
   * 列出 Redis 读模型里仍在进行的活动。完整路径是 {@code GET /query/promotions}。
   *
   * @return 进行中的活动；不查 PostgreSQL 活动表
   */
  @RequestMapping(method = RequestMethod.GET, value = "/promotions")
  public Collection<PromotionEntity> queryCurrent() {
    logger.info("Query current active promotions");
    return queryService.getActivePromotions();
  }

  /**
   * 搜索券。这是本控制器里唯一走到 Elasticsearch 的接口，完整路径是 {@code GET /query/coupons/search}。
   * <p>
   * {@code @RequestParam(required = false)} 表示查询字符串里的参数可以不传，不传时值为 {@code null}。
   *
   * @param customerId 可选的顾客编号
   * @param promotionId 可选的活动编号
   * @return 索引中匹配的券
   */
  @RequestMapping(method = RequestMethod.GET, value = "/coupons/search")
  public Collection<CouponInfo> search(@RequestParam(required = false) String customerId,
      @RequestParam(required = false) String promotionId) {
    return toInfo(queryService.search(customerId, promotionId));
  }

  /**
   * 把读模型中的券转成返回给浏览器的 {@link CouponInfo}。
   * <p>
   * {@code stream().map(...).collect(...)} 是对集合里每个元素做转换再收成列表，不访问存储。
   * {@code CouponEntity} 上的 {@code <String>} 表示顾客编号在本服务里是字符串。
   *
   * @param coupons Redis 或 Elasticsearch 返回的券
   * @return 与入参一一对应的响应对象
   */
  private Collection<CouponInfo> toInfo(Collection<CouponEntity<String>> coupons) {
    return coupons.stream()
        .map(coupon -> new CouponInfo(coupon.getId(), coupon.getCustomerId(), coupon.getPromotionId(),
            new Date(coupon.getTime()), coupon.getDiscount()))
        .collect(Collectors.toList());
  }
}
