import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react'
import { createPromotion, grabCoupon, listCoupons, listPromotions, type Coupon, type Promotion } from './api'
import './App.css'

/**
 * 把 Date 收成 datetime-local 需要的「年-月-日T时:分」。
 * 只给创建活动表单用，不请求后端。
 */
function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 把毫秒时间戳显示成浏览器本地时间，用在活动卡片和「我的券」上。
 */
function formatTime(epoch: number): string {
  return new Date(epoch).toLocaleString()
}

/**
 * 等待若干毫秒。创建活动后等读模型，抢券后也会隔一会儿再查 /query。
 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 秒杀柜台整页。浏览器只访问 nginx :8080，不连接 PostgreSQL。
 * 左侧表单走 /admin 创建活动；活动列表和「我的券」走 /query；「抢券」走 /command。
 * 抢券 HTTP 成功只表示请求被接受，所以下面会多次刷新，直到查询里出现这张券或次数用完。
 */
export default function App() {
  const now = useMemo(() => new Date(), [])
  const [publishTime, setPublishTime] = useState(toLocalInput(now))
  const [finishTime, setFinishTime] = useState(toLocalInput(new Date(now.getTime() + 24 * 60 * 60 * 1000)))
  const [numberOfCoupons, setNumberOfCoupons] = useState(10)
  const [discount, setDiscount] = useState(0.7)
  const [customerId, setCustomerId] = useState('user1')
  const [promotions, setPromotions] = useState<Promotion[]>([])
  const [coupons, setCoupons] = useState<Coupon[]>([])
  const [message, setMessage] = useState('')
  const [ok, setOk] = useState(false)
  const [busy, setBusy] = useState(false)

  /**
   * 同时请求 GET /query/promotions 和 GET /query/coupons/{顾客编号}，刷新右侧两块列表。
   * 顾客编号为空时不请求券接口，列表保持空数组。
   */
  const refresh = useCallback(async () => {
    const [nextPromotions, nextCoupons] = await Promise.all([
      listPromotions(),
      customerId.trim() ? listCoupons(customerId.trim()) : Promise.resolve([]),
    ])
    setPromotions(nextPromotions)
    setCoupons(nextCoupons)
  }, [customerId])

  useEffect(() => {
    refresh().catch((error: Error) => {
      setOk(false)
      setMessage(error.message)
    })
  }, [refresh])

  /**
   * 提交「创建活动」表单，调用 POST /admin/promotions/。
   * 成功后等一小会儿再 refresh，因为进行中的活动要等 Command 到点初始化后才会出现在 /query。
   */
  async function onCreate(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    try {
      const id = await createPromotion({
        numberOfCoupons,
        discount,
        publishTime: new Date(publishTime).getTime(),
        finishTime: new Date(finishTime).getTime(),
      })
      setOk(true)
      setMessage(`活动已创建：${id}`)
      await sleep(800)
      await refresh()
    } catch (error) {
      setOk(false)
      setMessage(error instanceof Error ? error.message : '创建失败')
    } finally {
      setBusy(false)
    }
  }

  /**
   * 用当前顾客编号抢指定活动，调用 POST /command/coupons/。
   * 成功后最多刷新 10 次 /query，直到「我的券」里出现这场活动，或次数用完。
   */
  async function onGrab(promotionId: string) {
    if (!customerId.trim()) {
      setOk(false)
      setMessage('请先填写用户 ID')
      return
    }
    setBusy(true)
    try {
      const result = await grabCoupon(promotionId, customerId.trim())
      setOk(true)
      setMessage(result.replace(/^"|"$/g, ''))
      for (let i = 0; i < 10; i += 1) {
        await sleep(800)
        await refresh()
        const latest = await listCoupons(customerId.trim())
        if (latest.some((coupon) => coupon.promotionId === promotionId)) {
          break
        }
      }
    } catch (error) {
      setOk(false)
      setMessage(error instanceof Error ? error.message : '抢券失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="page">
      <header className="hero">
        <div>
          <p className="eyebrow">ServiceComb SecKill</p>
          <h1>秒杀柜台</h1>
          <p className="lede">创建活动、抢券、查询已抢到的券。读模型经消息队列同步，抢券后可能稍等片刻才出现在查询结果里。</p>
        </div>
      </header>

      {message ? <p className={ok ? 'banner ok' : 'banner'}>{message}</p> : null}

      <div className="grid">
        <form className="panel" onSubmit={onCreate}>
          <h2>创建活动</h2>
          <label className="field">
            券数量
            <input
              type="number"
              min={1}
              value={numberOfCoupons}
              onChange={(event) => setNumberOfCoupons(Number(event.target.value))}
              required
            />
          </label>
          <label className="field">
            折扣（0 到 1，例如 0.7 为七折）
            <input
              type="number"
              min={0.01}
              max={1}
              step={0.01}
              value={discount}
              onChange={(event) => setDiscount(Number(event.target.value))}
              required
            />
          </label>
          <label className="field">
            开始时间
            <input type="datetime-local" value={publishTime} onChange={(event) => setPublishTime(event.target.value)} required />
          </label>
          <label className="field">
            结束时间
            <input type="datetime-local" value={finishTime} onChange={(event) => setFinishTime(event.target.value)} required />
          </label>
          <button className="primary" type="submit" disabled={busy}>
            {busy ? '处理中…' : '发布活动'}
          </button>
        </form>

        <section className="panel">
          <h2>进行中的活动</h2>
          <div className="toolbar">
            <input
              value={customerId}
              onChange={(event) => setCustomerId(event.target.value)}
              placeholder="用户 ID"
              aria-label="用户 ID"
            />
            <button className="ghost" type="button" onClick={() => refresh()} disabled={busy}>
              刷新
            </button>
          </div>
          {promotions.length === 0 ? (
            <p className="empty">暂无进行中的活动</p>
          ) : (
            <div className="list">
              {promotions.map((promotion) => (
                <article className="card" key={promotion.promotionId}>
                  <div>
                    <div className="id">{promotion.promotionId}</div>
                    <p className="meta">
                      {promotion.numberOfCoupons} 张 · {Math.round(promotion.discount * 10)} 折
                      <br />
                      {formatTime(promotion.publishTime)} — {formatTime(promotion.finishTime)}
                    </p>
                  </div>
                  <button className="grab" type="button" disabled={busy} onClick={() => onGrab(promotion.promotionId)}>
                    抢券
                  </button>
                </article>
              ))}
            </div>
          )}

          <h2 style={{ marginTop: 28 }}>我的券</h2>
          {coupons.length === 0 ? (
            <p className="empty">用户 {customerId || '—'} 还没有券</p>
          ) : (
            <div className="list">
              {coupons.map((coupon) => (
                <article className="card" key={`${coupon.promotionId}-${coupon.id}`}>
                  <div>
                    <div className="id">{coupon.promotionId}</div>
                    <p className="meta">
                      {coupon.customerId} · {Math.round(coupon.discount * 10)} 折
                      <br />
                      {formatTime(coupon.time)}
                    </p>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </main>
  )
}
