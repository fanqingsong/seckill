/*
 * ┌─ 文件 ──────────────────────────────┐
 * │ api.ts                              │
 * │ 浏览器 → nginx :8080                │
 * └────────────────────────────────────┘
 *
 * 浏览器
 * │
 * ▼
 * 【本文件】经 nginx :8080 封装 fetch
 * │
 * ├── POST /admin/promotions/ ──▶ 创建活动
 * ├── POST /command/coupons/ ──▶ 抢券
 * └── GET /query/... ──▶ 活动列表与我的券
 *
 * 一句话：只发 HTTP，不打开 PostgreSQL。
 */
/**
 * 查询接口返回的一场活动。
 * 浏览器只请求 nginx 的 8080，由它把 /query 转到 Query 服务；这里不连接 PostgreSQL。
 * publishTime、finishTime 是毫秒时间戳。
 */
export type Promotion = {
  id?: number
  promotionId: string
  publishTime: number
  finishTime: number
  numberOfCoupons: number
  discount: number
}

/**
 * 「我的券」里的一张券，对应 GET /query/coupons/{顾客编号} 返回数组中的一项。
 * time 是抢到的毫秒时间戳。浏览器仍只访问 nginx :8080。
 */
export type Coupon = {
  id: number
  customerId: string
  promotionId: string
  time: number
  discount: number
}

/**
 * 读完响应正文。状态不是 2xx 时抛出 Error，页面用 error.message 显示横幅。
 * 成功时返回原始文本，调用方再决定要不要 JSON.parse。
 */
async function readBody(response: Response): Promise<string> {
  const text = await response.text()
  if (!response.ok) {
    throw new Error(extractError(text, response.status))
  }
  return text
}

/**
 * 从失败响应里取出给人看的句子。
 * 能解析成 JSON 时用 message 或 error；HTML 或纯文本则去掉标签后截断。
 */
function extractError(text: string, status: number): string {
  try {
    const json = JSON.parse(text) as { message?: string; error?: string }
    return json.message || json.error || text || `HTTP ${status}`
  } catch {
    const stripped = text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()
    return stripped.slice(0, 240) || `HTTP ${status}`
  }
}

/**
 * 拉取进行中的活动，请求 GET /query/promotions。
 * 浏览器访问 nginx :8080 上的这个路径，不直接连 Query 端口，也不读 PostgreSQL。
 */
export async function listPromotions(): Promise<Promotion[]> {
  const text = await readBody(await fetch('/query/promotions'))
  return JSON.parse(text) as Promotion[]
}

/**
 * 拉取某位顾客已抢到的券，请求 GET /query/coupons/{customerId}。
 * customerId 会做 encodeURIComponent，避免编号里的特殊字符弄断路径。
 * 刚抢成功时这里可能还是空的，要等读模型追上。浏览器只走 nginx :8080。
 */
export async function listCoupons(customerId: string): Promise<Coupon[]> {
  const text = await readBody(await fetch(`/query/coupons/${encodeURIComponent(customerId)}`))
  return JSON.parse(text) as Coupon[]
}

/**
 * 创建活动，请求 POST /admin/promotions/，JSON 正文含券数量、折扣和两个时间戳。
 * 这是 Admin 链路：此时只是提交活动，页面不写 PostgreSQL。
 * 返回值是去掉首尾引号后的活动编号。浏览器只走 nginx :8080。
 */
export async function createPromotion(payload: {
  numberOfCoupons: number
  discount: number
  publishTime: number
  finishTime: number
}): Promise<string> {
  const text = await readBody(
    await fetch('/admin/promotions/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  )
  return text.replace(/^"|"$/g, '').trim()
}

/**
 * 为指定顾客抢一场活动的券，请求 POST /command/coupons/。
 * 返回的文本只表示 Command 接受了请求，查询页可能还要再等一会儿才出现这张券。
 * 浏览器只走 nginx :8080，不连接 PostgreSQL。
 */
export async function grabCoupon(promotionId: string, customerId: string): Promise<string> {
  return readBody(
    await fetch('/command/coupons/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ promotionId, customerId }),
    }),
  )
}
