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
import io.servicecomb.provider.rest.common.RestSchema;

@RestSchema(schemaId = "seckillSync")
@RestController
@RequestMapping("/sync")
public class SecKillSyncRestController {
  private static final Logger logger = LoggerFactory.getLogger(SecKillSyncRestController.class);

  @Autowired
  private SecKillQueryService queryService;

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
