// 页面只访问 Java 在 127.0.0.1 上提供的接口，不连接云端。
const hash = new URLSearchParams(location.hash.slice(1))
if (hash.has('token')) {
  sessionStorage.setItem('todo-token', hash.get('token'))
  history.replaceState(null, '', location.pathname)
}
export function token() { return sessionStorage.getItem('todo-token') || '' }
const windowId = crypto.randomUUID()

export async function request(path, body) {
  const response = await fetch(`/api/${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Authorization': `Bearer ${token()}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  })
  const result = await response.json()
  if (!response.ok) {
    const error = new Error(result.error || '操作失败，请重试。')
    error.status = response.status
    throw error
  }
  return result
}

export function windowEvent(event, note = {}) {
  navigator.sendBeacon('/api/window', new Blob([JSON.stringify({ event, windowId, token: token(), ...note })], { type: 'application/json' }))
}

export function localDate(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
export function shiftDate(value, days) {
  const date = new Date(`${value}T12:00:00`)
  date.setDate(date.getDate() + days)
  return localDate(date)
}
export function weekRange(value = localDate()) {
  const weekday = new Date(`${value}T12:00:00`).getDay()
  const from = shiftDate(value, -(weekday === 0 ? 6 : weekday - 1))
  return { from, to: shiftDate(from, 6) }
}

export const weekDayLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
export function weekdayOf(value) { return new Date(`${value}T12:00:00`).getDay() || 7 }
export function selectedWeekDays(task) {
  return [...new Set(task.weekDays?.length ? task.weekDays : [weekdayOf(task.start)])].sort((a, b) => a - b)
}
export function repeatLabel(task) {
  if (task.repeat === 'weekly') return `每周 · ${selectedWeekDays(task).map(day => weekDayLabels[day - 1]).join('、')}`
  return { once: '单次', daily: '每天', weekdays: '工作日', monthly: '每月' }[task.repeat] || '单次'
}
export function firstScheduledDate(task) {
  if (task.repeat !== 'weekly') return task.start
  const days = selectedWeekDays(task)
  return Array.from({ length: 7 }, (_, offset) => shiftDate(task.start, offset)).find(date => days.includes(weekdayOf(date)))
}
