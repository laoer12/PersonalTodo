import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { chromium } from 'playwright'
import { javaExecutable, browserOptions } from './environment.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const sandbox = await fs.mkdtemp(path.join(root, 'runtime/tmp/weekly-ui-'))
const environment = { ...process.env, TEMP: path.join(root, 'runtime/tmp'), TMP: path.join(root, 'runtime/tmp') }
const executable = process.env.TODO_EXE || javaExecutable
const args = process.env.TODO_EXE ? [] : ['-Xms16m', '-Xmx192m', '-XX:+UseSerialGC', '-XX:-UsePerfData', `-Dpersonal.todo.home=${root}`, `-Djava.io.tmpdir=${environment.TEMP}`, '-jar', path.join(root, 'build/input/personal-todo.jar')]
const infoFile = path.join(sandbox, 'server.json'), dataDir = path.join(sandbox, 'data')
const monday = '2026-10-12'
const shift = (value, days) => { const d = new Date(`${value}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10) }
let server, info, browser, checks = 0
const errors = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(predicate, label) {
  const end = Date.now() + 12000
  while (Date.now() < end) { if (await predicate()) return; await pause(100) }
  throw new Error(`Timed out: ${label}`)
}
function pass(label) { checks++; console.log(`PASS ${label}`) }
async function api(route, body) {
  const response = await fetch(`${info.origin}/api/${route}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${info.token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  return { status: response.status, value: await response.json() }
}
async function state() { const result = await api('state'); assert.equal(result.status, 200); return result.value }
async function mutate(fields) { return api('action', { revision: (await state()).revision, ...fields }) }
async function start() {
  server = spawn(executable, [...args, '--no-browser', '--data-dir', dataDir, '--server-info', infoFile], { cwd: root, env: environment, windowsHide: true, stdio: 'ignore' })
  await until(async () => { try { info = JSON.parse(await fs.readFile(infoFile, 'utf8')); return (await fetch(info.origin)).ok } catch { return false } }, 'server start')
}
async function stop() {
  if (server?.exitCode === null) { await api('exit', {}); await until(() => server.exitCode !== null, 'server exit') }
}
async function selectDays(container, days) {
  for (const [index, label] of ['周一', '周二', '周三', '周四', '周五', '周六', '周日'].entries()) {
    const checkbox = container.getByRole('checkbox', { name: label, exact: true })
    if (await checkbox.isChecked() !== days.includes(index + 1)) { await checkbox.focus(); await checkbox.press('Space') }
  }
}
try {
  await start()
  const legacy = { title: '原有每周任务', start: monday, repeat: 'weekly', priority: 0, category: '生活', time: null, notes: '', steps: [] }
  assert.equal((await mutate({ type: 'task.save', task: legacy })).status, 200)
  assert.deepEqual((await state()).data.tasks[0].weekDays, [1])
  pass('existing API clients retain the original start weekday')
  const before = await state()
  for (const weekDays of [[], [0], [8], ['1'], [1.5]]) assert.equal((await mutate({ type: 'task.save', task: { ...legacy, title: '无效日期', weekDays } })).status, 400)
  assert.deepEqual(await state(), before)
  pass('HTTP rejects empty and malformed weekly dates without changing state')
  browser = await chromium.launchPersistentContext(path.join(sandbox, 'browser-profile'), { ...browserOptions, headless: true, viewport: { width: 1104, height: 694 }, env: environment, args: [`--disk-cache-dir=${path.join(sandbox, 'browser-cache')}`, '--disable-breakpad', '--disable-crash-reporter'] })
  const page = await browser.newPage()
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  let confirmations = 0
  page.on('dialog', dialog => { assert.equal(dialog.type(), 'confirm'); confirmations++; dialog.accept() })
  await page.goto(info.url)
  await page.getByRole('heading', { name: '今天', exact: true }).waitFor()
  await page.getByRole('button', { name: '新建待办', exact: false }).click()
  const editor = page.getByRole('dialog', { name: '新建待办', exact: true })
  await editor.getByLabel('任务名称', { exact: true }).fill('每周学习记录')
  await editor.getByLabel('开始日期', { exact: true }).fill(monday)
  await editor.getByLabel('重复规则', { exact: true }).selectOption('weekly')
  assert.equal(await editor.getByRole('checkbox', { name: /^周/ }).count(), 7)
  assert(await editor.getByRole('checkbox', { name: '周一', exact: true }).isChecked())
  pass('weekly editor offers all seven weekdays and defaults to the chosen start date')
  await selectDays(editor, [])
  assert(await editor.getByRole('button', { name: '保存待办', exact: true }).isDisabled())
  await editor.getByRole('alert').getByText('请至少选择一个日期', { exact: true }).waitFor()
  await editor.getByLabel('任务名称', { exact: true }).press('Enter')
  assert.equal((await state()).data.tasks.length, 1)
  pass('empty weekday selection prevents submission and shows actionable validation')
  await selectDays(editor, [1, 3, 5])
  await editor.getByRole('checkbox', { name: '到点提醒', exact: true }).check()
  await editor.getByLabel('待办提醒时间', { exact: true }).fill('23:59')
  await editor.getByLabel('备注', { exact: true }).fill('每周学习笔记')
  await page.screenshot({ path: path.join(root, 'build/weekly-editor-desktop.png') })
  const editorSize = await editor.evaluate(element => ({ content: element.scrollHeight, height: element.clientHeight, fields: [...element.querySelectorAll('fieldset, .form-grid, .form-field, .modal-actions')].map(field => ({ className: field.className, height: field.getBoundingClientRect().height, margin: getComputedStyle(field).margin })) }))
  assert(editorSize.content <= editorSize.height + 1, JSON.stringify(editorSize))
  await editor.getByRole('button', { name: '保存待办', exact: true }).click()
  await editor.waitFor({ state: 'hidden' })
  const task = (await state()).data.tasks.find(t => t.title === '每周学习记录')
  assert.deepEqual(task.weekDays, [1, 3, 5]); assert.equal(task.time, '23:59')
  await page.locator(`.task-row[data-task-id="${task.id}"]`).getByText('每周 · 周一、周三、周五', { exact: true }).waitFor()
  pass('multiple weekdays and reminder time persist and display clearly in the task row')
  const range = (await api(`state?date=${monday}&from=${monday}&to=${shift(monday, 13)}`)).value.range
  assert.deepEqual(range.days.filter(day => day.taskIds.includes(task.id)).map(day => day.date), [0, 2, 4, 7, 9, 11].map(offset => shift(monday, offset)))
  pass('two-week plan contains the task only on Monday Wednesday Friday')
  assert.equal((await mutate({ type: 'task.complete', id: task.id, date: monday, value: true, completionReport: '已完成周一的学习。' })).status, 200)
  await page.reload(); await page.getByRole('heading', { name: '今天', exact: true }).waitFor()
  await page.getByRole('button', { name: '全部计划', exact: true }).click()
  await page.locator(`.task-row[data-task-id="${task.id}"]`).getByRole('button', { name: '编辑', exact: true }).click()
  const editing = page.getByRole('dialog', { name: '编辑待办', exact: true })
  for (const label of ['周一', '周三', '周五']) assert(await editing.getByRole('checkbox', { name: label, exact: true }).isChecked())
  await editing.getByLabel('开始日期', { exact: true }).fill(shift(monday, 1))
  assert(await editing.getByRole('checkbox', { name: '周一', exact: true }).isChecked())
  await editing.getByLabel('开始日期', { exact: true }).fill(monday)
  pass('editing restores saved choices and date changes do not overwrite explicit weekday selection')
  await selectDays(editing, [2, 4])
  await editing.getByRole('button', { name: '保存待办', exact: true }).click()
  await editing.waitFor({ state: 'hidden' })
  assert.equal(confirmations, 1)
  const changed = (await state()).data.tasks.find(t => t.id === task.id)
  assert.deepEqual(changed.weekDays, [2, 4]); assert(changed.done.includes(monday)); assert.equal(changed.completionReports[monday], '已完成周一的学习。')
  pass('weekday changes require confirmation and preserve dated completion records')
  await page.getByRole('button', { name: '历史与复盘', exact: true }).click()
  await page.getByLabel('复盘日期', { exact: true }).fill(monday)
  await page.getByRole('button', { name: '查看事项 每周学习记录', exact: true }).click()
  await page.getByText('已完成周一的学习。', { exact: true }).waitFor()
  assert((await page.locator('.history-plan-details summary').textContent()).includes('每周 · 周二、周四'))
  pass('history retains removed weekday occurrence and displays current weekly rule')
  await page.locator('nav').getByRole('button', { name: '今天', exact: true }).click()
  await page.getByLabel('选择日期', { exact: true }).fill(monday)
  const composer = page.locator('.composer')
  await composer.getByLabel('添加一个待办', { exact: true }).fill('快速添加每周运动')
  await composer.getByRole('button', { name: '重复规则', exact: true }).click()
  await page.getByRole('option', { name: '每周', exact: true }).click()
  await selectDays(composer, [4, 7])
  await page.screenshot({ path: path.join(root, 'build/weekly-composer-desktop.png') })
  await composer.getByRole('button', { name: '添加', exact: true }).click()
  await until(async () => (await state()).data.tasks.some(t => t.title === '快速添加每周运动'), 'weekly quick add')
  const quickTask = (await state()).data.tasks.find(t => t.title === '快速添加每周运动')
  assert.deepEqual(quickTask.weekDays, [4, 7]); assert.equal(await composer.locator('.weekday-picker').count(), 0)
  pass('quick composer supports selected weekdays and resets selection after saving')
  await page.getByRole('button', { name: '全部计划', exact: true }).click()
  await page.locator(`.task-row[data-task-id="${quickTask.id}"]`).getByRole('button', { name: '编辑', exact: true }).click()
  await editing.getByLabel('重复规则', { exact: true }).selectOption('daily')
  assert.equal(await editing.locator('.weekday-picker').count(), 0)
  await editing.getByRole('button', { name: '保存待办', exact: true }).click()
  await editing.waitFor({ state: 'hidden' })
  assert.deepEqual((await state()).data.tasks.find(t => t.id === quickTask.id).weekDays, [])
  pass('switching to daily hides weekday controls and removes the weekly restriction')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '新建待办', exact: false }).click()
  await editor.getByLabel('重复规则', { exact: true }).selectOption('weekly')
  await selectDays(editor, [2, 3, 5])
  await editor.locator('.weekday-picker').scrollIntoViewIfNeeded()
  for (const bounds of await editor.locator('.weekday-option').evaluateAll(elements => elements.map(element => ({ left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().right })))) assert(bounds.left >= 0 && bounds.right <= 390)
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  await page.screenshot({ path: path.join(root, 'build/weekly-editor-mobile.png') })
  await editor.getByRole('button', { name: '取消', exact: true }).click()
  pass('seven keyboard-operable weekday choices fit a narrow screen without horizontal overflow')
  await page.setViewportSize({ width: 1104, height: 694 })
  await page.getByRole('button', { name: '新建待办', exact: false }).click()
  await editor.getByLabel('任务名称', { exact: true }).fill('每周五回顾')
  await editor.getByLabel('开始日期', { exact: true }).fill(monday)
  await editor.getByLabel('重复规则', { exact: true }).selectOption('weekly')
  await selectDays(editor, [5])
  await editor.getByRole('button', { name: '保存待办', exact: true }).click()
  await editor.waitFor({ state: 'hidden' })
  await page.locator('.task-row').getByText('每周五回顾', { exact: true }).waitFor()
  assert.equal(await page.getByLabel('选择日期', { exact: true }).inputValue(), shift(monday, 4))
  const fridayTask = (await state()).data.tasks.find(t => t.title === '每周五回顾')
  assert.equal(fridayTask.start, monday); assert.deepEqual(fridayTask.weekDays, [5])
  pass('new weekly task opens its first selected date while retaining the original start boundary')
  await browser.close(); browser = null
  await stop(); await start()
  const restored = (await state()).data.tasks.find(t => t.id === task.id)
  assert.deepEqual(restored.weekDays, [2, 4]); assert.equal(restored.completionReports[monday], '已完成周一的学习。')
  pass('server restart restores weekday selection and historical report')
  assert.deepEqual(errors, []); pass('weekly interactions produce no browser console or JavaScript errors')
  await fs.writeFile(path.join(root, 'build/weekly-ui-results.json'), JSON.stringify({ date: new Date().toISOString(), checks, sandbox, executable }, null, 2))
  console.log(`All ${checks} weekly/UI checks passed. Sandbox: ${sandbox}`)
} finally {
  if (browser) await browser.close()
  if (server?.exitCode === null) { try { await stop() } catch { server.kill() } }
}
