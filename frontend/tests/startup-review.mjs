import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { chromium } from 'playwright'
import { javaExecutable, browserOptions } from './environment.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const sandbox = await fs.mkdtemp(path.join(root, 'runtime/tmp/startup-review-ui-'))
const dataDir = path.join(sandbox, 'data'), infoFile = path.join(sandbox, 'server.json'), receiptFile = path.join(dataDir, 'startup-review.json')
const environment = { ...process.env, TEMP: path.join(root, 'runtime/tmp'), TMP: path.join(root, 'runtime/tmp') }
const executable = process.env.TODO_EXE || javaExecutable
const args = process.env.TODO_EXE ? [] : ['-Xms16m', '-Xmx192m', '-XX:+UseSerialGC', '-XX:-UsePerfData', `-Dpersonal.todo.home=${root}`, `-Djava.io.tmpdir=${environment.TEMP}`, '-jar', path.join(root, 'build/input/personal-todo.jar')]
const shift = (value, days) => { const date = new Date(`${value}T12:00:00Z`); date.setUTCDate(date.getUTCDate() + days); return date.toISOString().slice(0, 10) }
let server, info, browser, checks = 0
const errors = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(predicate, label) {
  const end = Date.now() + 12000
  while (Date.now() < end) { if (await predicate()) return; await pause(100) }
  throw new Error(`Timed out: ${label}`)
}
function pass(label) { checks++; console.log(`PASS ${label}`) }
async function api(route, body, headers = {}) {
  const response = await fetch(`${info.origin}/api/${route}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${info.token}`, 'Content-Type': 'application/json', ...headers }, body: body === undefined ? undefined : JSON.stringify(body) })
  return { status: response.status, value: await response.json() }
}
async function state() { const result = await api('state'); assert.equal(result.status, 200); return result.value }
async function mutate(fields) { const response = await api('action', { revision: (await state()).revision, ...fields }); assert.equal(response.status, 200); return response.value }
async function start() {
  server = spawn(executable, [...args, '--no-browser', '--data-dir', dataDir, '--server-info', infoFile], { cwd: root, env: environment, windowsHide: true, stdio: 'ignore' })
  await until(async () => { try { info = JSON.parse(await fs.readFile(infoFile, 'utf8')); return (await fetch(info.origin)).ok } catch { return false } }, 'server start')
}
async function stop() {
  if (server?.exitCode === null) { await api('exit', {}); await until(() => server.exitCode !== null, 'server exit') }
}
async function resetReceipt() {
  assert(receiptFile.startsWith(path.join(root, 'runtime/tmp') + path.sep))
  await fs.rm(receiptFile, { force: true })
}
try {
  await start()
  const today = (await state()).today, yesterday = shift(today, -1)
  assert.equal((await fetch(`${info.origin}/api/startup-review`, { method: 'POST', body: '{}' })).status, 401)
  assert.equal((await api('startup-review', {}, { Origin: 'https://example.com' })).status, 403)
  assert.equal((await api('startup-review')).status, 405)
  assert.equal((await api('startup-review', { windowId: 'invalid' })).status, 400)
  assert.equal((await fetch(`${info.origin}/startup-review.json`)).status, 404)
  pass('daily review API requires authentication, same origin, POST and a valid window owner')
  const report = '完成了 props 与 emit 的练习，整理了两个组件通信示例。\n能够说明父子组件的数据传递，下一步练习状态共享。'
  for (const [title, category] of [['学习 Vue 组件通信', '学习'], ['完成项目日报', '工作'], ['散步 30 分钟', '健康'], ['昨日未完成', '生活'], ['昨日已顺延', '生活'], ['前天已完成', '生活'], ['今天已完成', '学习']]) {
    await mutate({ type: 'task.save', task: { title, category, repeat: 'daily', start: shift(today, -30), priority: 0, notes: '', steps: [], time: null } })
  }
  const samples = (await state()).data.tasks
  await mutate({ type: 'task.complete', id: samples[0].id, date: yesterday, value: true, completionReport: report })
  await mutate({ type: 'task.complete', id: samples[1].id, date: yesterday, value: true, completionReport: '已提交日报，记录本周进度、待解决问题和下一步安排。' })
  await mutate({ type: 'task.complete', id: samples[2].id, date: yesterday, value: true })
  await mutate({ type: 'task.defer', id: samples[4].id, date: yesterday })
  await mutate({ type: 'task.complete', id: samples[5].id, date: shift(today, -2), value: true })
  await mutate({ type: 'task.complete', id: samples[6].id, date: today, value: true })
  const first = (await state()).data.tasks[0]
  await mutate({ type: 'task.save', task: { ...first, start: shift(today, 5), steps: [] }, expected: first })
  const before = await fs.readFile(path.join(dataDir, 'data.json')), initialRevision = (await state()).revision
  browser = await chromium.launchPersistentContext(path.join(sandbox, 'browser-profile'), { ...browserOptions, headless: true, viewport: { width: 1487, height: 1058 }, env: environment, args: [`--disk-cache-dir=${path.join(sandbox, 'browser-cache')}`, '--disable-breakpad', '--disable-crash-reporter'] })
  const page = await browser.newPage()
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  await page.goto(info.url)
  const modal = page.getByRole('dialog', { name: '昨日回顾', exact: true })
  await modal.waitFor()
  assert.equal(await modal.locator('.yesterday-item').count(), 3)
  await modal.getByRole('heading', { name: '学习 Vue 组件通信', exact: true }).waitFor()
  await modal.getByText(report, { exact: true }).waitFor()
  assert.equal(await modal.getByText('未填写完成情况', { exact: true }).count(), 1)
  for (const title of ['昨日未完成', '昨日已顺延', '前天已完成', '今天已完成']) assert.equal(await modal.getByText(title, { exact: true }).count(), 0)
  assert.deepEqual(await fs.readFile(path.join(dataDir, 'data.json')), before)
  assert.equal((await state()).revision, initialRevision)
  await modal.getByRole('heading', { name: '昨日回顾', exact: true }).click()
  await page.screenshot({ path: path.join(root, 'build/yesterday-review-desktop.png') })
  pass('first opening lists all yesterday completed tasks and full reports, including missing report and edited recurrence')
  const close = modal.getByRole('button', { name: '关闭昨日回顾', exact: true }), begin = modal.getByRole('button', { name: '开始今天', exact: true })
  await close.focus(); await close.press('Shift+Tab'); assert(await begin.evaluate(element => element === document.activeElement))
  await begin.press('Tab'); assert(await close.evaluate(element => element === document.activeElement))
  await close.press('Control+n'); assert.equal(await page.getByRole('dialog', { name: '新建待办', exact: true }).count(), 0)
  assert(await page.locator('main').evaluate(element => element.inert))
  pass('focus stays inside review and background navigation and shortcuts cannot interrupt reading')
  const second = await browser.newPage(); await second.goto(info.url)
  await second.getByRole('heading', { name: '今天', exact: true }).waitFor()
  assert.equal(await second.getByRole('dialog', { name: '昨日回顾', exact: true }).count(), 0)
  assert.equal(await modal.count(), 1); await second.close()
  pass('opening another window does not show a second daily popup')
  await page.setViewportSize({ width: 1104, height: 694 })
  const footer = await modal.locator('.yesterday-footer').boundingBox()
  assert(footer.y + footer.height <= 694)
  assert(await modal.evaluate(element => element.scrollHeight <= element.clientHeight + 1))
  await page.screenshot({ path: path.join(root, 'build/yesterday-review-compact.png') })
  pass('compact desktop keeps heading and both footer actions visible without scrolling the whole modal')
  await close.click(); await modal.waitFor({ state: 'hidden' })
  await page.reload(); await page.getByRole('heading', { name: '今天', exact: true }).waitFor()
  assert.equal(await modal.count(), 0)
  pass('closing then refreshing keeps the popup dismissed for the day')
  await stop(); await start(); await page.goto(info.url)
  await page.getByRole('heading', { name: '今天', exact: true }).waitFor(); assert.equal(await modal.count(), 0)
  pass('daily receipt survives app restart independent of browser origin')
  await resetReceipt(); await page.reload(); await modal.waitFor()
  await page.route('**/api/state?*', async route => {
    if (new URL(route.request().url()).searchParams.get('date') === yesterday) await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: '模拟复盘读取失败' }) })
    else await route.continue()
  })
  await modal.getByRole('button', { name: '查看昨日复盘', exact: true }).click()
  await modal.getByRole('alert').waitFor(); assert.equal(await modal.count(), 1)
  await page.unroute('**/api/state?*')
  await modal.getByRole('button', { name: '查看昨日复盘', exact: true }).click(); await modal.waitFor({ state: 'hidden' })
  assert.equal(await page.getByLabel('复盘日期', { exact: true }).inputValue(), yesterday)
  await page.getByRole('button', { name: '查看事项 学习 Vue 组件通信', exact: true }).click()
  await page.locator('.history-report-text').getByText(report, { exact: true }).waitFor()
  pass('yesterday history action retries failed navigation and opens the exact dated reports')
  const longReport = Array.from({ length: 45 }, (_, index) => `第 ${index + 1} 项完成内容：整理组件示例和下一步计划。`).join('\n')
  await mutate({ type: 'task.report', id: samples[0].id, date: yesterday, completionReport: longReport, expectedCompletion: { done: true, report } })
  await resetReceipt(); await page.reload(); await modal.waitFor()
  const reader = modal.getByRole('region', { name: '昨日已完成事项', exact: true })
  assert(await reader.evaluate(element => element.scrollHeight > element.clientHeight))
  await reader.focus(); await reader.press('End')
  await until(async () => await reader.evaluate(element => element.scrollTop > 0), 'reader scroll')
  assert(await begin.isVisible()); assert((await modal.locator('.yesterday-footer').boundingBox()).y < 694)
  await page.screenshot({ path: path.join(root, 'build/yesterday-review-long-report.png') })
  pass('long completion content scrolls with keyboard inside reader while footer stays fixed')
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  const narrowFooter = await modal.locator('.yesterday-footer').boundingBox()
  assert(narrowFooter.x >= 0 && narrowFooter.x + narrowFooter.width <= 390 && narrowFooter.y + narrowFooter.height <= 844)
  await page.screenshot({ path: path.join(root, 'build/yesterday-review-mobile.png') })
  await modal.press('Escape'); await modal.waitFor({ state: 'hidden' })
  assert.equal(await page.locator('main').evaluate(element => element.inert), false)
  pass('narrow review has no horizontal overflow and Escape closes it and restores background')
  await resetReceipt(); await page.reload(); await modal.waitFor(); await begin.click(); await modal.waitFor({ state: 'hidden' })
  await page.getByRole('heading', { name: '今天', exact: true }).waitFor()
  pass('start today closes review and leaves current day ready to use')
  await resetReceipt()
  await page.route('**/api/startup-review', route => route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: '模拟读取失败' }) }))
  await page.reload(); await page.getByRole('button', { name: '重试昨日回顾', exact: true }).waitFor()
  await assert.rejects(fs.access(receiptFile))
  await page.unroute('**/api/startup-review')
  await page.getByRole('button', { name: '重试昨日回顾', exact: true }).click(); await modal.waitFor()
  await begin.click()
  pass('failed request leaves daily slot available and retry recovers the review')
  await resetReceipt()
  await page.route('**/api/startup-review', async route => { await route.fetch(); await route.abort('failed') })
  await page.reload(); await page.getByRole('button', { name: '重试昨日回顾', exact: true }).waitFor(); await fs.access(receiptFile)
  await page.unroute('**/api/startup-review')
  await page.getByRole('button', { name: '重试昨日回顾', exact: true }).click(); await modal.waitFor(); await begin.click()
  pass('lost response can be retried by the same owner after server recorded the receipt')
  await resetReceipt()
  for (const task of (await state()).data.tasks.filter(task => task.done.includes(yesterday))) await mutate({ type: 'task.delete', id: task.id })
  await page.reload(); await page.getByRole('heading', { name: '今天', exact: true }).waitFor(); assert.equal(await modal.count(), 0)
  await fs.access(receiptFile)
  pass('no completed tasks skips popup while recording the first opening')
  assert.deepEqual(errors.filter(message => !message.includes('500 (Internal Server Error)') && !message.includes('net::ERR_FAILED')), [])
  pass('review produces no unexpected console or JavaScript errors')
  await fs.writeFile(path.join(root, 'build/startup-review-ui-results.json'), JSON.stringify({ date: new Date().toISOString(), checks, sandbox, executable, errors }, null, 2))
  console.log(`All ${checks} startup review/UI checks passed. Sandbox: ${sandbox}`)
} finally {
  if (browser) await browser.close()
  if (server?.exitCode === null) { try { await stop() } catch { server.kill() } }
}
