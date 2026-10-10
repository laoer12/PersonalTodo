import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { chromium } from 'playwright'
import { javaExecutable, browserOptions } from './environment.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const sandbox = await fs.mkdtemp(path.join(root, 'runtime/tmp/completion-e2e-'))
const dataDir = path.join(sandbox, 'data'), infoFile = path.join(sandbox, 'server.json')
const env = { ...process.env, TEMP: path.join(root, 'runtime/tmp'), TMP: path.join(root, 'runtime/tmp') }
let server, info, browser, checks = 0, output = ''
const errors = [], expectedErrors = [], pause = ms => new Promise(resolve => setTimeout(resolve, ms))
function pass(text) { checks++; console.log(`PASS ${text}`) }
async function until(fn, name, timeout = 16000) { const end = Date.now() + timeout; while (Date.now() < end) { if (await fn()) return; await pause(100) } throw new Error(`Timed out: ${name}\n${output}`) }
async function api(route, body) { const response = await fetch(`${info.origin}/api/${route}`, { method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${info.token}`, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }); return { status: response.status, data: await response.json() } }
async function state() { return (await api('state')).data }
async function mutate(fields) { return api('action', { revision: (await state()).revision, ...fields }) }
async function start() {
  const exe = process.env.TODO_EXE || javaExecutable
  const args = process.env.TODO_EXE ? [] : ['-Xms16m', '-Xmx192m', '-XX:-UsePerfData', `-Dpersonal.todo.home=${root}`, `-Djava.io.tmpdir=${env.TEMP}`, '-jar', path.join(root, 'build/input/personal-todo.jar')]
  server = spawn(exe, [...args, '--no-browser', '--data-dir', dataDir, '--server-info', infoFile], { cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] }); server.stdout.on('data', t => output += t); server.stderr.on('data', t => output += t)
  await until(async () => { try { info = JSON.parse(await fs.readFile(infoFile, 'utf8')); return (await fetch(info.origin)).ok } catch { return false } }, 'server ready')
}
async function stop() { if (server?.exitCode === null) { await api('exit', {}); await until(() => server.exitCode !== null, 'server exit') } }
try {
  await start(); const today = (await state()).today
  const task = { title: '完成 Vue 组件练习', start: today, repeat: 'daily', priority: 2, category: '学习', notes: '', steps: ['写一个组件', '整理学习笔记'] }
  assert.equal((await mutate({ type: 'task.save', task })).status, 200); const id = (await state()).data.tasks[0].id
  browser = await chromium.launchPersistentContext(path.join(sandbox, 'browser-profile'), { ...browserOptions, headless: true, viewport: { width: 1160, height: 820 }, env, args: [`--disk-cache-dir=${path.join(sandbox, 'browser-cache')}`, '--disable-breakpad', '--disable-crash-reporter'] })
  const page = await browser.newPage(); page.on('pageerror', e => errors.push(e.message)); page.on('dialog', d => d.accept()); page.on('console', m => { if (m.type() === 'error') (/status of (409|500)/.test(m.text()) ? expectedErrors : errors).push(m.text()) })
  await page.goto(info.url); const checkbox = page.getByRole('checkbox', { name: `完成 ${task.title}`, exact: true })
  const modal = page.getByRole('dialog', { name: '记录完成情况' })
  await checkbox.click(); await modal.waitFor(); assert.equal(await checkbox.isChecked(), false); assert.equal((await state()).data.tasks[0].done.length, 0); assert(await modal.getByLabel('完成情况', { exact: true }).evaluate(el => el === document.activeElement)); pass('today completion opens focused dialog before marking done')
  await modal.getByLabel('完成情况', { exact: true }).fill('取消的文字'); await modal.getByRole('button', { name: '取消', exact: true }).click(); await modal.waitFor({ state: 'hidden' }); assert.equal(await checkbox.isChecked(), false); pass('cancel keeps today task incomplete')
  await checkbox.click(); await modal.waitFor(); await page.keyboard.press('Escape'); await modal.waitFor({ state: 'hidden' }); assert.equal((await state()).data.tasks[0].done.length, 0); pass('Escape closes dialog without saving or completing')
  await checkbox.click(); await modal.waitFor(); const report = '完成了组件拆分和属性传递练习。\n还有事件通信需要继续巩固。'
  await modal.getByLabel('完成情况', { exact: true }).fill(report); await page.screenshot({ path: path.join(root, 'build/completion-dialog.png') })
  await modal.getByRole('button', { name: '保存并完成', exact: true }).click(); await modal.waitFor({ state: 'hidden' }); let saved = (await state()).data.tasks[0]
  assert(saved.done.includes(today)); assert.equal(saved.completionReports[today], report); assert(saved.steps.every(s => s.done.includes(today))); pass('submit commits report and parent/subtask completion together')
  await page.getByRole('button', { name: '完成情况', exact: true }).click(); assert.equal(await page.locator('.completion-report p').textContent(), report); pass('saved report can be expanded in task row')
  await page.reload(); await until(async () => await page.getByRole('button', { name: '完成情况', exact: true }).count() === 1, 'report after reload'); pass('completion report survives page reload')
  await checkbox.uncheck(); await until(async () => !(await state()).data.tasks[0].done.includes(today), 'undo completion'); assert.equal((await state()).data.tasks[0].completionReports[today], report)
  await checkbox.click(); await modal.waitFor(); assert.equal(await modal.getByLabel('完成情况', { exact: true }).inputValue(), report); pass('uncheck preserves report and recheck pre-fills it')
  const failedDraft = '保存失败时保留这段文字'; await modal.getByLabel('完成情况', { exact: true }).fill(failedDraft)
  await page.route('**/api/action', async route => { if (route.request().postDataJSON().type === 'task.complete') await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: '模拟完成情况保存失败' }) }); else await route.continue() })
  await modal.getByRole('button', { name: '保存并完成', exact: true }).click(); await modal.getByRole('alert').waitFor(); assert.equal(await modal.getByLabel('完成情况', { exact: true }).inputValue(), failedDraft); assert(!(await state()).data.tasks[0].done.includes(today)); pass('save failure retains text and does not mark completed')
  await page.unroute('**/api/action'); await modal.getByRole('button', { name: '保存并完成', exact: true }).click(); await modal.waitFor({ state: 'hidden' }); assert.equal((await state()).data.tasks[0].completionReports[today], failedDraft); pass('failed completion can be retried successfully')
  await checkbox.uncheck(); await until(async () => !(await state()).data.tasks[0].done.includes(today), 'undo for conflict'); await checkbox.click(); await modal.waitFor(); await modal.getByLabel('完成情况', { exact: true }).fill('本窗口的草稿')
  await mutate({ type: 'task.complete', id, date: today, value: true, completionReport: '另一窗口的新版情况' }); await modal.getByRole('button', { name: '保存并完成', exact: true }).click(); await modal.getByRole('alert').waitFor(); assert.equal(await modal.getByLabel('完成情况', { exact: true }).inputValue(), '本窗口的草稿'); assert.equal((await state()).data.tasks[0].completionReports[today], '另一窗口的新版情况'); pass('conflicting window update preserves both visible draft and saved report')
  await modal.getByRole('button', { name: '取消', exact: true }).click(); await checkbox.uncheck(); await until(async () => !(await state()).data.tasks[0].done.includes(today), 'undo after conflict')
  await page.getByRole('button', { name: '本周计划', exact: true }).click(); const weekCheck = page.locator(`.period-day[data-date="${today}"]`).getByRole('checkbox', { name: `完成 ${task.title}`, exact: true }); await weekCheck.click(); await modal.waitFor(); assert.equal(await modal.getByLabel('完成情况', { exact: true }).inputValue(), '另一窗口的新版情况'); await modal.getByRole('button', { name: '取消', exact: true }).click(); pass('today occurrence in weekly view uses the same completion dialog')
  await page.getByRole('button', { name: '明天', exact: true }).click(); await checkbox.check(); await until(async () => (await state()).data.tasks[0].done.length === 1, 'tomorrow completion'); assert.equal(await modal.count(), 0); assert.equal((await state()).data.tasks[0].completionReports[today], '另一窗口的新版情况'); pass('tomorrow completion does not modify today report or require dialog')
  await page.getByRole('button', { name: '今天', exact: true }).click(); await checkbox.click(); await modal.waitFor(); await modal.getByLabel('完成情况', { exact: true }).fill(''); await modal.getByRole('button', { name: '保存并完成', exact: true }).click(); await modal.waitFor({ state: 'hidden' }); assert((await state()).data.tasks[0].done.includes(today)); assert.equal((await state()).data.tasks[0].completionReports[today], undefined); pass('optional empty report can complete today task')
  await checkbox.uncheck(); await until(async () => !(await state()).data.tasks[0].done.includes(today), 'undo for mobile'); await page.setViewportSize({ width: 390, height: 844 }); await checkbox.click(); await modal.waitFor(); const input = modal.getByLabel('完成情况', { exact: true }); await input.fill('窄窗口下也能记录完成情况'); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); const bounds = await modal.boundingBox(); assert(bounds.x >= 0 && bounds.x + bounds.width <= 390); await page.screenshot({ path: path.join(root, 'build/completion-mobile.png') }); await modal.getByRole('button', { name: '保存并完成', exact: true }).click(); await modal.waitFor({ state: 'hidden' }); pass('completion dialog fits narrow window and submits')
  const latest = (await state()).data.tasks[0].completionReports[today]; await page.close(); await stop(); await start(); assert.equal((await state()).data.tasks[0].completionReports[today], latest); pass('completion reports survive backend restart')
  const exported = await mutate({ type: 'backup.export' }); const backup = JSON.parse(await fs.readFile(exported.data.path, 'utf8')); assert.equal(backup.tasks[0].completionReports[today], latest); pass('completion reports included in full backup')
  assert.deepEqual(errors, []); pass('completion interactions have no unexpected browser errors')
  await fs.writeFile(path.join(root, 'build/completion-e2e-results.json'), JSON.stringify({ checks, sandbox, errors, expectedErrors, testedExecutable: process.env.TODO_EXE || 'build/input/personal-todo.jar' }, null, 2)); console.log(`All ${checks} completion checks passed.`)
} finally { if (browser) await browser.close(); try { await stop() } catch { if (server?.exitCode === null) server.kill() } }
