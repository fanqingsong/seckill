export type Promotion = {
  id?: number
  promotionId: string
  publishTime: number
  finishTime: number
  numberOfCoupons: number
  discount: number
}

export type Coupon = {
  id: number
  customerId: string
  promotionId: string
  time: number
  discount: number
}

async function readBody(response: Response): Promise<string> {
  const text = await response.text()
  if (!response.ok) {
    throw new Error(extractError(text, response.status))
  }
  return text
}

function extractError(text: string, status: number): string {
  try {
    const json = JSON.parse(text) as { message?: string; error?: string }
    return json.message || json.error || text || `HTTP ${status}`
  } catch {
    const stripped = text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()
    return stripped.slice(0, 240) || `HTTP ${status}`
  }
}

export async function listPromotions(): Promise<Promotion[]> {
  const text = await readBody(await fetch('/query/promotions'))
  return JSON.parse(text) as Promotion[]
}

export async function listCoupons(customerId: string): Promise<Coupon[]> {
  const text = await readBody(await fetch(`/query/coupons/${encodeURIComponent(customerId)}`))
  return JSON.parse(text) as Coupon[]
}

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

export async function grabCoupon(promotionId: string, customerId: string): Promise<string> {
  return readBody(
    await fetch('/command/coupons/', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ promotionId, customerId }),
    }),
  )
}
