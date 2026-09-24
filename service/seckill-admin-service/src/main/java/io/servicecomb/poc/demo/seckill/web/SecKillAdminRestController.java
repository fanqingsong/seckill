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

import static org.springframework.http.HttpStatus.OK;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.servicecomb.poc.demo.seckill.dto.PromotionDto;
import io.servicecomb.poc.demo.seckill.entities.EventEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;

/**
 * 创建和修改活动的 HTTP 入口。数据只写入 PostgreSQL，不写 Redis、Kafka 或 Elasticsearch。
 * <p>
 * {@code @RestController} 表示本类接收 HTTP，方法返回值直接写成响应体。
 * {@code @RequestMapping("/admin/promotions")} 是类上的路径前缀。浏览器经 nginx 把 {@code /admin}
 * 转到 Gateway，Gateway 再转到本服务（8081）。创建的完整路径是 {@code POST /admin/promotions/}。
 * <p>
 * 构造器上的 {@code @Autowired} 是构造器注入：Spring 创建本类时，把两个仓库对象传进来。
 * {@code promotionRepository} 读写活动表；{@code eventRepository} 只在修改时用来判断活动是否已经开始。
 * 活动行保存成功后，Redis 里仍然没有库存。Command 服务要等到 {@code publishTime} 才初始化库存。
 */
@RestController
@RequestMapping("/admin/promotions")
public class SecKillAdminRestController {

  private static final Logger logger = LoggerFactory.getLogger(SecKillAdminRestController.class);

  private final SpringPromotionRepository promotionRepository;

  private final SpringSecKillEventRepository eventRepository;

  /**
   * @param promotionRepository 活动表，创建和修改都写这里
   * @param eventRepository 事件表，只用来判断有没有 {@code PromotionStartEvent}
   */
  @Autowired
  public SecKillAdminRestController(SpringPromotionRepository promotionRepository,
      SpringSecKillEventRepository eventRepository) {
    this.promotionRepository = promotionRepository;
    this.eventRepository = eventRepository;
  }

  /**
   * 新建一场活动，只向 PostgreSQL 插入一行活动记录。
   * <p>
   * {@code @RequestBody} 表示 Spring 把请求 JSON 反序列化成 {@link PromotionDto}。
   * 校验通过才 {@code save}；校验失败抛 {@link ResponseStatusException}，状态码 400。
   * 返回 200 和活动编号时，Redis 库存尚未初始化。
   *
   * @param promotionDto 开始时间、结束时间、券数量和折扣
   * @return 正文是新活动的 promotionId
   */
  @RequestMapping(method = RequestMethod.POST, value = "/")
  public ResponseEntity<String> create(@RequestBody PromotionDto promotionDto) {
    // 数量、折扣和时间都合法：写入活动表，不访问 Redis。
    if (isValidPromotion(promotionDto)) {
      PromotionEntity promotion = new PromotionEntity(promotionDto.getPublishTime(), promotionDto.getFinishTime(),
          promotionDto.getNumberOfCoupons(),
          promotionDto.getDiscount());
      promotionRepository.save(promotion);
      logger.info(
          "Created a new promotion id = {}, number = {}, discount = {}, publishTime = {}, finishTime = {}",
          promotion.getPromotionId(),
          promotion.getNumberOfCoupons(),
          promotion.getDiscount(),
          promotion.getPublishTime(),
          promotion.getFinishTime());

      return new ResponseEntity<>(promotion.getPromotionId(), OK);
    }

    // 字段不合法：不写库，HTTP 400。
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
        "Invalid promotion {numberOfCoupons=%d, discount=%f, publishTime=%s, finishTime=%s}",
        promotionDto.getNumberOfCoupons(),
        promotionDto.getDiscount(),
        promotionDto.getPublishTime(),
        promotionDto.getFinishTime()));
  }

  /**
   * 修改尚未开始的活动。已经出现 {@code PromotionStartEvent} 时拒绝修改。
   * <p>
   * {@code @PathVariable} 表示路径里的 {@code {promotionId}} 填进参数。完整路径是
   * {@code PUT /admin/promotions/{promotionId}}。仍然只更新 PostgreSQL 活动行，不改 Redis。
   *
   * @param promotionId 要修改的活动编号，空字符串视为非法
   * @param promotionDto 新的时间、券数量和折扣
   * @return 正文仍是原来的 promotionId
   */
  @RequestMapping(method = RequestMethod.PUT, value = "/{promotionId}")
  public ResponseEntity<String> modify(@PathVariable String promotionId,
      @RequestBody PromotionDto promotionDto) {
    // 编号非空且新字段合法，才去查库。
    if (!promotionId.isEmpty() && isValidPromotion(promotionDto)) {
      PromotionEntity promotion = promotionRepository.findTopByPromotionId(promotionId);
      // 活动行存在，再看事件表里有没有「已经开始」。
      if (promotion != null) {
        List<EventEntity> events = eventRepository.findByPromotionId(promotionId);
        // 还没有任何事件，或者事件里没有 PromotionStartEvent：允许改活动行。
        if (events.isEmpty() || events.stream()
            .noneMatch(event -> SecKillEventType.PromotionStartEvent.equals(event.getType()))) {
          promotion.setDiscount(promotionDto.getDiscount());
          promotion.setNumberOfCoupons(promotionDto.getNumberOfCoupons());
          promotion.setPublishTime(promotionDto.getPublishTime());
          promotion.setFinishTime(promotionDto.getFinishTime());
          promotionRepository.save(promotion);
          return new ResponseEntity<>(promotion.getPromotionId(), OK);
        }
        // 已经开始：库存可能已在 Redis，拒绝改活动定义。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            String.format("PromotionEntity had started and changes is rejected {promotionId=%s}", promotionId));
      }
      // 活动编号在表里找不到。
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          String.format("PromotionEntity not exists {promotionId=%s}", promotionId));
    }
    // 编号为空，或新字段不合法。
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
        "Invalid promotion {numberOfCoupons=%d, discount=%f, publishTime=%s,, finishTime=%s}",
        promotionDto.getNumberOfCoupons(),
        promotionDto.getDiscount(),
        promotionDto.getPublishTime(),
        promotionDto.getFinishTime()));
  }

  /**
   * 判断一场活动的字段能不能写入。
   * <p>
   * 券数量和折扣必须为正，折扣不超过 1；开始、结束时间都要有，结束必须晚于开始，并且结束时间还在将来。
   * 参数上的 {@code @RequestBody} 在这个私有方法里不起「读 HTTP 体」的作用，调用方已经传入对象。
   *
   * @param create 待检查的活动字段
   * @return {@code true} 表示可以保存；{@code false} 表示调用方应返回 400
   */
  private boolean isValidPromotion(@RequestBody PromotionDto create) {
    return create.getNumberOfCoupons() > 0
        && create.getDiscount() > 0
        && create.getDiscount() <= 1
        && create.getPublishTime() != null
        && create.getFinishTime().getTime() > create.getPublishTime().getTime()
        && create.getFinishTime().getTime() > System.currentTimeMillis();
  }
}
