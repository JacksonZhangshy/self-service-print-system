// 订单/任务状态与文案的唯一来源，避免各页面各写一套导致不一致

export const ORDER_STATUS = {
  CREATED: { label: '待支付', tone: 'warn' },
  PAYING: { label: '支付中', tone: 'warn' },
  PAID: { label: '已支付', tone: 'ok' },
  PRINTING: { label: '打印中', tone: 'ok' },
  PRINTED: { label: '已完成', tone: 'done' },
  PRINT_FAILED: { label: '打印失败', tone: 'bad' },
  CANCELLED: { label: '已取消', tone: 'mute' },
  EXPIRED: { label: '已过期', tone: 'mute' },
  REFUNDING: { label: '退款中', tone: 'warn' },
  REFUNDED: { label: '已退款', tone: 'mute' }
}

export const TASK_STATUS = {
  PENDING: { label: '待领取', tone: 'warn' },
  PRINTING: { label: '打印中', tone: 'ok' },
  SUCCESS: { label: '成功', tone: 'done' },
  FAILED: { label: '失败', tone: 'bad' },
  CANCELLED: { label: '已取消', tone: 'mute' }
}

export const REFUND_STATUS = {
  NONE: { label: '无', tone: 'mute' },
  PROCESSING: { label: '处理中', tone: 'warn' },
  SUCCESS: { label: '成功', tone: 'done' },
  FAILED: { label: '失败', tone: 'bad' }
}

export function orderStatus(s) {
  return ORDER_STATUS[s] || { label: s || '-', tone: 'mute' }
}

export function taskStatus(s) {
  return TASK_STATUS[s] || { label: s || '-', tone: 'mute' }
}

export function refundStatus(s) {
  return REFUND_STATUS[s] || { label: s || '-', tone: 'mute' }
}

export const ORDER_STATUS_OPTIONS = Object.entries(ORDER_STATUS).map(([value, v]) => ({
  value,
  label: v.label
}))

export const TASK_STATUS_OPTIONS = Object.entries(TASK_STATUS).map(([value, v]) => ({
  value,
  label: v.label
}))

export const PAY_METHOD_LABEL = { WECHAT: '微信支付', ALIPAY: '支付宝' }

export const COLOR_LABEL = { BW: '黑白', COLOR: '彩色' }
// 注意：后端枚举是 SINGLE / DUPLEX（早期写成 DOUBLE 会导致界面直接显示英文枚举）
export const DUPLEX_LABEL = { SINGLE: '单面', DUPLEX: '双面' }

/** 订单是否还能付款 */
export function payable(order) {
  return (
    !!order &&
    (order.status === 'CREATED' || order.status === 'PAYING') &&
    (order.paySecondsLeft === undefined || order.paySecondsLeft > 0)
  )
}

export function money(v) {
  if (v === null || v === undefined || v === '') return '0.00'
  return Number(v).toFixed(2)
}

/** 后端返回的是 LocalDateTime.toString()（2026-01-01T10:00:00），这里转成可读格式 */
export function dt(v) {
  if (!v) return '-'
  const s = String(v).replace('T', ' ')
  return s.length > 19 ? s.slice(0, 19) : s
}

export function mmss(seconds) {
  const s = Math.max(0, Math.floor(seconds || 0))
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
}
