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
import io.servicecomb.provider.rest.common.RestSchema;

@RestSchema(schemaId = "seckillQuery")
@RestController
@RequestMapping("/query")
public class SeckillQueryRestController {

  private static final Logger logger = LoggerFactory.getLogger(SeckillQueryRestController.class);

  @Autowired
  private SecKillQueryService queryService;

  @RequestMapping(method = RequestMethod.GET, value = "/coupons/{customerId}")
  public Collection<CouponInfo> querySuccess(@PathVariable("customerId") String customerId) {
    logger.info("Query customer id = {} coupons", customerId);
    return toInfo(queryService.getCustomerCoupons(customerId));
  }

  @RequestMapping(method = RequestMethod.GET, value = "/promotions")
  public Collection<PromotionEntity> queryCurrent() {
    logger.info("Query current active promotions");
    return queryService.getActivePromotions();
  }

  @RequestMapping(method = RequestMethod.GET, value = "/coupons/search")
  public Collection<CouponInfo> search(@RequestParam(required = false) String customerId,
      @RequestParam(required = false) String promotionId) {
    return toInfo(queryService.search(customerId, promotionId));
  }

  private Collection<CouponInfo> toInfo(Collection<CouponEntity<String>> coupons) {
    return coupons.stream()
        .map(coupon -> new CouponInfo(coupon.getId(), coupon.getCustomerId(), coupon.getPromotionId(),
            new Date(coupon.getTime()), coupon.getDiscount()))
        .collect(Collectors.toList());
  }
}
