import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { chromium } from 'playwright'
import { javaExecutable, browserOptions } from './environment.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
assert(path.isAbsolute(root))
const sandbox = await fs.mkdtemp(path.join(root, 'runtime/tmp/history-ui-'))
const environment = { ...process.env, TEMP: path.join(root, 'runtime/tmp'), TMP: path.join(root, 'runtime/tmp') }
const executable = process.env.TODO_EXE || javaExecutable
const args = process.env.TODO_EXE ? [] : ['-Xms16m', '-Xmx192m', '-XX:+UseSerialGC', '-XX:-UsePerfData', `-Dpersonal.todo.home=${root}`, `-Djava.io.tmpdir=${environment.TEMP}`, '-jar', path.join(root, 'build/input/personal-todo.jar')]
const infoFile = path.join(sandbox, 'server.json')
let server, info, browser, checks = 0
const errors = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(predicate, message) {
  const deadline = Date.now() + 12000
  while (Date.now() < deadline) { if (await predicate()) return; await pause(100) }
  throw new Error(`Timed out: ${message}`)
}
function pass(message) { checks++; console.log(`PASS ${message}`) }
async function api(route, body) {
  const response = await fetch(`${info.origin}/api/${route}`, { method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${info.token}`, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  assert.equal(response.status, 200)
  return response.json()
}
async function mutate(fields) { const state = await api('state'); return api('action', { revision: state.revision, ...fields }) }
const shift = (value, days) => { const date = new Date(`${value}T12:00:00Z`); date.setUTCDate(date.getUTCDate() + days); return date.toISOString().slice(0, 10) }
try {
  server = spawn(executable, [...args, '--no-browser', '--data-dir', path.join(sandbox, 'data'), '--server-info', infoFile], { cwd: root, env: environment, windowsHide: true, stdio: 'ignore' })
  await until(async () => { try { info = JSON.parse(await fs.readFile(infoFile, 'utf8')); return (await fetch(info.origin)).ok } catch { return false } }, 'server startup')
  const today = (await api('state')).today, previous = shift(today, -5), older = shift(today, -12)
  await mutate({ type: 'task.save', task: { title: '学习 Vue 与 Java', start: shift(today, -30), repeat: 'daily', priority: 2, category: '学习', notes: '', time: null, steps: [] } })
  const taskId = (await api('state')).data.tasks[0].id
  await mutate({ type: 'task.complete', id: taskId, date: previous, value: true, completionReport: '完成了日期选择组件，整理了学习笔记。' })
  await mutate({ type: 'note.save', date: previous, text: '组件拆分后更容易理解，下一步练习事件通信。' })
  await mutate({ type: 'note.save', date: older, text: '练习 Java 的集合与文件读写。' })
  browser = await chromium.launchPersistentContext(path.join(sandbox, 'browser-profile'), { ...browserOptions, headless: true, viewport: { width: 1160, height: 820 }, env: environment, args: [`--disk-cache-dir=${path.join(sandbox, 'browser-cache')}`, '--disable-breakpad', '--disable-crash-reporter'] })
  const page = await browser.newPage()
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  await page.goto(info.url)
  await page.getByRole('heading', { name: '今天', exact: true }).waitFor()
  await page.getByText('学习 Vue 与 Java', { exact: true }).waitFor()
  await page.getByLabel('添加一个待办', { exact: true }).fill('整理本周学习计划')
  const repeat = page.getByRole('button', { name: '重复规则', exact: true })
  await repeat.click()
  assert.equal(await page.getByRole('option').count(), 5)
  assert.equal(await page.getByRole('option').first().evaluate(element => getComputedStyle(element).fontSize), '14px')
  await page.screenshot({ path: path.join(root, 'build/composer-dropdown.png'), fullPage: true })
  pass('larger dropdown displays all five recurrence options')
  await page.getByRole('listbox').press('End')
  await page.getByRole('listbox').press('Enter')
  assert.equal(await repeat.textContent(), '每月')
  assert.equal((await api('state')).data.tasks.length, 1)
  pass('keyboard selection returns focus and does not submit the task form')
  await repeat.press('ArrowDown')
  await page.getByRole('listbox').press('Home')
  await page.getByRole('listbox').press('Escape')
  assert.equal(await repeat.textContent(), '每月')
  assert.equal(await repeat.evaluate(element => element === document.activeElement), true)
  pass('Escape cancels selection and restores focus')
  await repeat.click()
  await page.getByRole('listbox').press('Tab')
  assert.equal(await page.getByRole('listbox').count(), 0)
  assert.equal(await page.getByRole('button', { name: '优先级', exact: true }).evaluate(element => element === document.activeElement), true)
  pass('Tab closes the menu and moves to the next field')
  await repeat.click()
  await page.getByRole('heading', { name: '今天', exact: true }).click()
  assert.equal(await page.getByRole('listbox').count(), 0)
  pass('outside click closes the dropdown')
  await repeat.click(); await page.getByRole('option', { name: '每天', exact: true }).click()
  await page.getByRole('button', { name: '优先级', exact: true }).click(); await page.getByRole('option', { name: '高', exact: true }).click()
  await page.getByRole('button', { name: '添加', exact: true }).click()
  await until(async () => (await api('state')).data.tasks.length === 2, 'quick add')
  const created = (await api('state')).data.tasks.find(task => task.title === '整理本周学习计划')
  assert.equal(created.repeat, 'daily'); assert.equal(created.priority, 3)
  pass('new dropdowns persist correct recurrence and numeric priority')
  await page.getByRole('button', { name: '历史与复盘', exact: true }).click()
  const reviewDate = page.getByLabel('复盘日期', { exact: true }), review = page.getByLabel('一句话复盘', { exact: true })
  await reviewDate.waitFor()
  assert.equal(await reviewDate.inputValue(), shift(today, -1))
  assert.equal(await page.getByText('输入后自动保存到 D 盘', { exact: true }).count(), 0)
  pass('history defaults to yesterday without the storage-location hint')
  await reviewDate.fill(previous)
  await until(async () => await review.inputValue() === '组件拆分后更容易理解，下一步练习事件通信。', 'older review')
  assert.equal(await page.getByRole('heading', { name: '历史与复盘', exact: true }).count(), 1)
  assert.equal(await page.getByLabel('完成 学习 Vue 与 Java', { exact: true }).isChecked(), true)
  await page.getByRole('button', { name: '完成情况', exact: true }).click()
  await page.getByText('完成了日期选择组件，整理了学习笔记。', { exact: true }).waitFor()
  await page.screenshot({ path: path.join(root, 'build/history-review.png'), fullPage: true })
  pass('arbitrary earlier date shows its review, completion and report in history')
  await review.fill('修改后的五天前复盘，切换时立即保存。')
  await page.getByRole('button', { name: '前一天', exact: true }).click()
  await until(async () => await reviewDate.inputValue() === shift(previous, -1), 'previous day')
  assert.equal((await api('state')).data.notes[previous], '修改后的五天前复盘，切换时立即保存。')
  assert.equal(await review.inputValue(), '')
  assert.equal(await page.getByRole('heading', { name: '历史与复盘', exact: true }).count(), 1)
  pass('date arrows save the outgoing review to its date and stay in history')
  await page.getByRole('button', { name: '后一天', exact: true }).click()
  await until(async () => await review.inputValue() === '修改后的五天前复盘，切换时立即保存。', 'return to previous review')
  await page.getByLabel('选择日期', { exact: true }).fill(older)
  await until(async () => await review.inputValue() === '练习 Java 的集合与文件读写。', 'sidebar history date')
  assert.equal(await page.getByRole('heading', { name: '历史与复盘', exact: true }).count(), 1)
  pass('sidebar date selection also keeps history mode')
  await page.locator('.history-presets').getByRole('button', { name: '今天', exact: true }).click()
  await until(async () => await reviewDate.inputValue() === today, 'today preset')
  await page.locator('.history-presets').getByRole('button', { name: '昨天', exact: true }).click()
  await until(async () => await reviewDate.inputValue() === shift(today, -1), 'yesterday preset')
  pass('today and yesterday shortcuts select the right review date')
  await page.setViewportSize({ width: 390, height: 844 })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  await page.screenshot({ path: path.join(root, 'build/history-review-mobile.png'), fullPage: true })
  pass('history date controls fit a narrow viewport')
  await page.locator('nav').getByRole('button', { name: '今天', exact: true }).click()
  await repeat.waitFor(); await repeat.click()
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  const menu = await page.getByRole('listbox').boundingBox()
  assert(menu.x >= 0 && menu.x + menu.width <= 390)
  await page.screenshot({ path: path.join(root, 'build/composer-dropdown-mobile.png'), fullPage: true })
  pass('composer and dropdown fit a narrow viewport')
  assert.deepEqual(errors, []); pass('browser has no console or JavaScript errors')
  await fs.writeFile(path.join(root, 'build/history-ui-results.json'), JSON.stringify({ date: new Date().toISOString(), checks, errors, sandbox, executable }, null, 2))
  console.log(`All ${checks} history/UI checks passed. Sandbox: ${sandbox}`)
} finally {
  if (browser) await browser.close()
  if (server?.exitCode === null) { try { await api('exit', {}); await until(() => server.exitCode !== null, 'server exit') } catch { server.kill() } }
}
