/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ GrabToken.java                                │
 * │ 链路：抢券 · 队列令牌                         │
 * └───────────────────────────────────────────────┘
 *
 * Lua 扣减成功
 *      │
 *      ▼
 *  【本文件】拼正文
 *      │
 *      ▼
 *  grab stream
 *
 * 一句话：它是 grab stream 的正文，尚不是券记录。
 */

package io.servicecomb.poc.demo.seckill.redis;

/**
 * 热路径扣减成功后放进抢券队列的一张令牌。
 * <p>
 * Command 的 Lua（或内存实现里对应的一段逻辑）扣完库存后生成它。Persist 再取出来，
 * 在同一个数据库事务里写成 {@code CouponGrabbedEvent} 和 outbox。它不是查询页上的券，
 * 也不是 Kafka 消息。队列里的正文是用制表符拼起来的一段文本：活动编号、顾客编号、序号、剩余张数。
 */
public final class GrabToken {

  /** 队列正文四个字段之间的分隔符，必须和 Lua 里拼接 payload 的制表符一致。 */
  private static final String SEP = "\t";

  private final String promotionId;
  private final String customerId;
  private final long seq;
  private final long remaining;
  private final String payload;
  /** Redis Stream 条目 id。内存队列不填；Jedis 读到条目后才填，供 XACK / XDEL 使用。 */
  private final String messageId;

  /**
   * 扣减成功时创建令牌，此时还没有队列条目 id。
   *
   * @param promotionId 活动编号
   * @param customerId 抢到的顾客
   * @param seq 这次扣减分配到的事件序号
   * @param remaining 扣减之后还剩的张数。0 表示这一张是最后一张
   */
  public GrabToken(String promotionId, String customerId, long seq, long remaining) {
    this(promotionId, customerId, seq, remaining, null);
  }

  /**
   * 组装令牌，并把四个业务字段拼成队列正文。正文里不含 {@code messageId}。
   *
   * @param messageId Redis Stream 的条目 id。刚扣减成功时为 null
   */
  private GrabToken(String promotionId, String customerId, long seq, long remaining, String messageId) {
    this.promotionId = promotionId;
    this.customerId = customerId;
    this.seq = seq;
    this.remaining = remaining;
    this.payload = promotionId + SEP + customerId + SEP + seq + SEP + remaining;
    this.messageId = messageId;
  }

  /**
   * 把队列里的正文还原成令牌。
   *
   * @param payload 制表符分隔的四段文本。空串或 null 表示这条队列记录没有正文
   * @return 还原后的令牌，尚无 messageId。正文为空时返回 null
   * @throws IllegalArgumentException 正文不是恰好四段时抛出，调用方应视为坏数据
   */
  public static GrabToken parse(String payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    // 第四段可能本身含分隔符，因此最多切成四段，避免把剩余张数再拆开。
    String[] parts = payload.split(SEP, 4);
    if (parts.length != 4) {
      throw new IllegalArgumentException("Invalid grab payload: " + payload);
    }
    return new GrabToken(parts[0], parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3]));
  }

  /** 活动编号。 */
  public String getPromotionId() {
    return promotionId;
  }

  /** 顾客编号。 */
  public String getCustomerId() {
    return customerId;
  }

  /** 扣减时分配的事件序号，Persist 落库时沿用它。 */
  public long getSeq() {
    return seq;
  }

  /** 扣减之后的剩余张数。0 表示卖完最后一张。 */
  public long getRemaining() {
    return remaining;
  }

  /** 写入抢券队列的正文，四个字段用制表符连接。 */
  public String payload() {
    return payload;
  }

  /** Redis Stream 条目 id。还没从 Stream 读出时为 null。 */
  public String getMessageId() {
    return messageId;
  }

  /**
   * 复制一份带队列条目 id 的令牌。业务字段和正文保持不变。
   * <p>
   * Jedis 实现读到 Stream 条目后调用。相等判断只看正文，所以加上 id 后仍和原令牌相等。
   *
   * @param messageId Redis Stream 条目 id，形如毫秒-序号
   * @return 新令牌。原对象不变
   */
  public GrabToken withMessageId(String messageId) {
    return new GrabToken(promotionId, customerId, seq, remaining, messageId);
  }

  /**
   * 只比较队列正文。两条令牌的 messageId 不同、正文相同，仍然视为同一条。
   * 内存实现靠这个从「处理中」队列里移除令牌。
   */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GrabToken)) {
      return false;
    }
    return payload.equals(((GrabToken) other).payload);
  }

  /** 与 {@link #equals} 一致，只对正文求哈希。 */
  @Override
  public int hashCode() {
    return payload.hashCode();
  }
}
