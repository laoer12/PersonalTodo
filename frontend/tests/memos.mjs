import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { chromium } from 'playwright'
import { javaExecutable, browserOptions } from './environment.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
assert(path.isAbsolute(root))
const sandbox = await fs.mkdtemp(path.join(root, 'runtime/tmp/memo-e2e-'))
const dataDir = path.join(sandbox, 'data'), infoFile = path.join(sandbox, 'server.json')
const env = { ...process.env, TEMP: path.join(root, 'runtime/tmp'), TMP: path.join(root, 'runtime/tmp') }
let server, info, browser, count = 0, output = ''
const errors = [], consoleErrors = [], pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const pass = text => { count++; console.log(`PASS ${text}`) }
async function until(fn, text, timeout = 16000) { const end = Date.now() + timeout; while (Date.now() < end) { if (await fn()) return; await pause(100) } throw new Error(`Timed out: ${text}\n${output}`) }
async function api(route, body) { const response = await fetch(`${info.origin}/api/${route}`, { method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${info.token}`, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }); return { status: response.status, data: await response.json() } }
async function memos() { return (await api('memos')).data.memos }
async function menu(page, label) { if (!await page.locator('.memo-menu').evaluate(el => el.open)) await page.getByLabel('备忘录更多操作').click(); await page.getByRole('button', { name: label, exact: true }).click() }
async function start() {
  const java = process.env.TODO_EXE || javaExecutable
  const args = process.env.TODO_EXE ? [] : ['-Xms16m', '-Xmx192m', '-XX:-UsePerfData', `-Dpersonal.todo.home=${root}`, `-Djava.io.tmpdir=${env.TEMP}`, '-jar', path.join(root, 'build/input/personal-todo.jar')]
  server = spawn(java, [...args, '--no-browser', '--data-dir', dataDir, '--server-info', infoFile], { cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  server.stdout.on('data', text => output += text); server.stderr.on('data', text => output += text)
  await until(async () => { try { info = JSON.parse(await fs.readFile(infoFile, 'utf8')); return (await fetch(info.origin)).ok } catch { return false } }, 'server ready')
}
async function stop() { if (server && server.exitCode === null) { await api('exit', {}); await until(() => server.exitCode !== null, 'server shutdown') } }
try {
  await start()
  browser = await chromium.launchPersistentContext(path.join(sandbox, 'browser-profile'), { ...browserOptions, headless: true, viewport: { width: 1160, height: 820 }, env, args: [`--disk-cache-dir=${path.join(sandbox, 'browser-cache')}`, '--disable-breakpad', '--disable-crash-reporter'] })
  const page = await browser.newPage(); page.on('pageerror', error => errors.push(error.message)); page.on('dialog', dialog => dialog.accept()); page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })
  await page.goto(info.url); await page.getByRole('button', { name: '备忘录', exact: true }).click()
  await page.getByText('给想法留一个地方', { exact: true }).waitFor(); pass('memo navigation and empty state work')
  await page.locator('.memo-list-pane').getByRole('button', { name: '新建备忘录', exact: true }).click()
  await page.waitForTimeout(950); assert.equal((await memos()).length, 0); pass('blank draft is not persisted')
  await page.getByLabel('备忘录标题').fill('Vue 学习笔记')
  const body = '# 学习计划\n\n- [x] 组件练习\n- 搜索与保存\n\n```js\nconst message = "hello"\n```\n\n| 功能 | 状态 |\n| --- | --- |\n| 保存 | 完成 |\n\n[资料](https://vuejs.org)'
  await page.getByLabel('备忘录正文').fill(body)
  await until(async () => (await memos())[0]?.body === body, 'Markdown autosave'); const id = (await memos())[0].id
  await page.getByRole('button', { name: '预览', exact: true }).click()
  await page.getByLabel('Markdown 预览').getByRole('heading', { name: '学习计划', exact: true }).waitFor()
  assert.equal(await page.locator('.memo-preview pre code').textContent(), 'const message = "hello"\n')
  assert.equal(await page.locator('.memo-preview table').count(), 1); pass('Markdown headings, lists, code, tables and links render')
  await page.getByRole('button', { name: '编辑', exact: true }).click(); await page.getByLabel('备忘录正文').fill('<script>window.memoXss = 1</script>\n\n[bad](javascript:alert(1))\n\n![remote](https://invalid.example/image.png)')
  await page.keyboard.press('Control+s'); await until(async () => (await memos())[0].body.includes('memoXss'), 'safe preview body save')
  await page.getByRole('button', { name: '预览', exact: true }).click()
  assert.equal(await page.locator('.memo-preview script,.memo-preview img,.memo-preview a[href^="javascript:"]').count(), 0)
  assert.equal(await page.evaluate(() => window.memoXss), undefined); pass('preview never executes raw HTML or loads remote images')
  await page.getByRole('button', { name: '编辑', exact: true }).click(); await page.getByLabel('备忘录正文').fill(body)
  await page.getByRole('button', { name: '今天', exact: true }).click(); assert.equal((await memos())[0].body, body); pass('navigation flushes unsaved memo immediately')
  await page.getByRole('button', { name: '备忘录', exact: true }).click()
  await page.getByLabel('备忘录正文').fill(`${body}\n\n新的段落`)
  await page.locator('.memo-list-item').first().click(); assert((await page.getByLabel('备忘录正文').inputValue()).includes('新的段落')); pass('selecting current memo cannot revert a just-saved edit')
  await page.keyboard.press('Control+n'); await until(async () => (await page.getByLabel('备忘录标题').inputValue()) === '', 'new draft ready'); await page.getByLabel('备忘录标题').fill('第二篇记录'); await page.getByLabel('备忘录正文').fill('正文搜索唯一词：组件通信')
  await page.locator('.memo-list-item').filter({ hasText: 'Vue 学习笔记' }).click()
  await until(async () => (await memos()).length === 2, 'second memo persisted'); pass('new-note shortcut and note switching save the draft')
  await page.getByLabel('搜索备忘录').fill('组件通信'); assert.equal(await page.locator('.memo-list-item').count(), 1); await page.getByLabel('搜索备忘录').fill(''); pass('search matches memo body')
  await menu(page, '置顶备忘录'); await until(async () => (await memos()).find(m => m.id === id)?.pinned, 'pin save'); pass('pin persists without changing note text')
  await page.route('**/api/memos', async route => {
    if (route.request().method() === 'POST' && route.request().postDataJSON().type === 'save') await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: '模拟磁盘保存失败，草稿保留' }) })
    else await route.continue()
  })
  await page.getByLabel('备忘录正文').fill('必须保留的失败草稿'); await page.getByText('模拟磁盘保存失败，草稿保留', { exact: true }).waitFor()
  await page.getByRole('button', { name: '今天', exact: true }).click(); assert.equal(await page.getByLabel('备忘录正文').inputValue(), '必须保留的失败草稿'); pass('failed autosave retains draft and blocks lossy navigation')
  await page.unroute('**/api/memos'); await page.getByRole('button', { name: '重试保存', exact: true }).click()
  await until(async () => (await memos()).find(m => m.id === id)?.body === '必须保留的失败草稿', 'retry save'); pass('retry recovers failed save')
  const second = await browser.newPage(); second.on('pageerror', e => errors.push(e.message)); second.on('dialog', d => d.accept())
  await second.goto(info.url); await second.getByRole('button', { name: '备忘录', exact: true }).click(); await second.getByLabel('备忘录正文').waitFor()
  await page.getByLabel('备忘录正文').fill('第一个窗口的新版'); await page.keyboard.press('Control+s'); await until(async () => (await memos()).find(m => m.id === id)?.body === '第一个窗口的新版', 'first window save')
  await second.getByLabel('备忘录正文').fill('第二个窗口的草稿'); await second.getByRole('button', { name: '另存草稿', exact: true }).waitFor()
  assert.equal((await memos()).find(m => m.id === id).body, '第一个窗口的新版'); pass('two-window edit conflict preserves original and visible draft')
  await second.getByRole('button', { name: '另存草稿', exact: true }).click(); await until(async () => (await memos()).some(m => m.body === '第二个窗口的草稿'), 'save conflict copy'); pass('conflict draft can be saved as a new memo')
  await second.close()
  await menu(page, '导出 Markdown'); await until(async () => { try { return (await fs.readdir(path.join(dataDir, 'exports'))).some(name => name.endsWith('.md')) } catch { return false } }, 'Markdown export'); pass('single memo exports Markdown on D drive')
  await menu(page, '移入回收站'); await until(async () => (await memos()).find(m => m.id === id)?.deletedAt, 'soft delete')
  await page.getByRole('button', { name: '回收站', exact: true }).click(); await page.getByLabel('备忘录标题').waitFor(); assert.equal(await page.getByLabel('备忘录正文').getAttribute('readonly'), ''); pass('recycle bin retains content as read-only')
  await menu(page, '恢复备忘录'); await until(async () => !(await memos()).find(m => m.id === id)?.deletedAt, 'restore'); pass('restore returns note to active collection')
  await page.getByRole('button', { name: '返回备忘录', exact: true }).click(); await page.locator('.memo-list-item').filter({ hasText: 'Vue 学习笔记' }).click()
  const chinese = page.getByLabel('备忘录正文'); await chinese.dispatchEvent('compositionstart'); await chinese.fill('中文输入组合过程'); await page.waitForTimeout(1100)
  assert.notEqual((await memos()).find(m => m.id === id).body, '中文输入组合过程'); await chinese.dispatchEvent('compositionend'); await until(async () => (await memos()).find(m => m.id === id)?.body === '中文输入组合过程', 'composition save'); pass('IME composition defers autosave until text is committed')
  const finalDraft = '关闭前最后的草稿'; await chinese.fill(finalDraft); await page.evaluate(() => window.dispatchEvent(new Event('pagehide')))
  await until(async () => (await memos()).find(m => m.id === id)?.body === finalDraft, 'pagehide save'); pass('pagehide beacon commits final small draft')
  await page.keyboard.press('Control+s'); await page.getByRole('button', { name: '今天', exact: true }).click()
  await page.close(); await stop(); await start()
  assert.equal((await memos()).find(m => m.id === id).body, finalDraft); pass('memos survive backend restart')

  // Design fixtures are written only into this test sandbox through normal backup import.
  const s = (await api('state')).data
  const referenceBody = '今天的练习\n\n把备忘录做成独立组件，梳理数据和事件。\n\n三个要点\n\n组件负责展示，数据通过属性传入。\n用户操作通过事件通知父组件。\n保存状态要让人一眼看懂。\n\n明天继续\n\n练习列表搜索和自动保存。'
  const samples = [['Vue 学习笔记', referenceBody], ['这周想做的事', '整理项目进度，准备下周的计划安排…'], ['工作交接清单', '前端项目的交接事项和注意点…'], ['随手想法', '关于产品的一些灵感，先记录下来…'], ['阅读摘录', '《被讨厌的勇气》：重要的不是过去…']]
  const fixture = { ...s.data, memos: samples.map(([title, body], index) => ({ id: crypto.randomUUID(), title, body, pinned: index === 0, version: 1, createdAt: '2026-10-08T00:00:00Z', updatedAt: index < 2 ? '2026-10-09T06:20:00Z' : '2026-10-08T04:00:00Z', deletedAt: null })) }
  assert.equal((await api('action', { type: 'backup.import', revision: s.revision, data: fixture })).status, 200)
  const previewPage = await browser.newPage(); previewPage.on('pageerror', e => errors.push(e.message)); await previewPage.goto(info.url); await previewPage.getByRole('button', { name: '备忘录', exact: true }).click(); await previewPage.getByLabel('备忘录正文').waitFor()
  await previewPage.screenshot({ path: path.join(root, 'build/memo-preview.png') })
  const cdp = await browser.newCDPSession(previewPage); await cdp.send('DOM.enable'); await cdp.send('CSS.enable')
  const { root: documentNode } = await cdp.send('DOM.getDocument')
  const { nodeId } = await cdp.send('DOM.querySelector', { nodeId: documentNode.nodeId, selector: '.memo-list-title' })
  const fonts = await cdp.send('CSS.getPlatformFontsForNode', { nodeId }); await cdp.detach()
  await previewPage.getByRole('button', { name: '预览', exact: true }).click(); await previewPage.screenshot({ path: path.join(root, 'build/memo-markdown-preview.png') })
  await previewPage.setViewportSize({ width: 390, height: 844 }); assert.equal(await previewPage.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  const saveFooter = await previewPage.locator('.memo-save-footer').boundingBox(); assert(saveFooter.y + saveFooter.height <= 844, 'mobile save state stays inside viewport')
  await previewPage.screenshot({ path: path.join(root, 'build/memo-mobile.png') }); await previewPage.getByRole('button', { name: '笔记列表', exact: true }).click(); await previewPage.getByLabel('搜索备忘录').waitFor()
  assert.equal(await previewPage.evaluate(() => document.documentElement.scrollWidth > innerWidth), false); pass('mobile editor and back-to-list have no horizontal overflow')

  console.log(`Memo platform fonts: ${JSON.stringify(fonts)}`)
  assert(consoleErrors.every(message => /Failed to load resource: the server responded with a status of (409|500)/.test(message)), `Unexpected console errors: ${consoleErrors}`)
  assert.deepEqual(errors, []); pass('memo flows produce no unhandled JavaScript errors')
  await fs.writeFile(path.join(root, 'build/memo-e2e-results.json'), JSON.stringify({ checks: count, sandbox, errors, consoleErrors, fonts, screenshot: 'build/memo-preview.png', testedExecutable: process.env.TODO_EXE || 'build/input/personal-todo.jar' }, null, 2))
  console.log(`All ${count} memo browser checks passed.`)
} finally {
  if (browser) await browser.close()
  try { await stop() } catch { if (server?.exitCode === null) server.kill() }
}
