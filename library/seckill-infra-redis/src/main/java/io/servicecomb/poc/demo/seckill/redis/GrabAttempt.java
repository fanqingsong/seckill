package io.servicecomb.poc.demo.seckill.redis;

/**
 * 热路径 {@link SecKillStore#tryGrab} 的一次结果。
 * <p>
 * 它只描述 Redis（或内存替身）里这次判断和扣减的结论，不表示 PostgreSQL 已经写下券。
 * 四个状态码和 Jedis 实现里 Lua 的返回值一致：成功 1，重复 -1，卖完 -2，库存键还不存在 -3。
 */
public class GrabAttempt {

  /** Lua 返回 1：已把顾客放入已抢集合，库存减 1，并写入抢券队列。 */
  public static final int SUCCESS = 1;
  /** Lua 返回 -1：该顾客已在已抢集合里，本次不扣库存。 */
  public static final int DUPLICATE = -1;
  /** Lua 返回 -2：库存键存在，但剩余张数已经不大于 0。 */
  public static final int SOLD_OUT = -2;
  /** Lua 返回 -3：库存键还不存在，活动尚未初始化热路径。 */
  public static final int NOT_STARTED = -3;

  private final int code;
  private final long seq;
  private final long remaining;

  /**
   * @param code 上面四个常量之一
   * @param seq 只有成功时是这次分配的序号；失败时实现传入 0
   * @param remaining 成功时是扣减后的剩余张数。重复时内存实现会带上当前库存，Jedis 的 Lua 失败时传 0
   */
  public GrabAttempt(int code, long seq, long remaining) {
    this.code = code;
    this.seq = seq;
    this.remaining = remaining;
  }

  /** 状态码是否为成功。为 true 只表示热路径已扣减。 */
  public boolean isSuccess() {
    return code == SUCCESS;
  }

  /** 状态码是否为重复顾客。为 true 时库存没有再次减少。 */
  public boolean isDuplicate() {
    return code == DUPLICATE;
  }

  /**
   * 状态码是否为卖完或尚未开始。
   * <p>
   * 两个失败码都返回 true。Command 在重复判断之后，把其余非成功都当成抢券失败，不再访问数据库。
   *
   * @return 卖完（-2）或库存尚未初始化（-3）时为 true
   */
  public boolean isSoldOut() {
    return code == SOLD_OUT || code == NOT_STARTED;
  }

  /** 成功时的事件序号；失败时为构造时传入的值，一般为 0。 */
  public long getSeq() {
    return seq;
  }

  /** 成功时扣减后的剩余张数。 */
  public long getRemaining() {
    return remaining;
  }
}
