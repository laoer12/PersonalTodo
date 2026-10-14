<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { request, windowEvent, windowId, localDate, shiftDate, weekRange, firstScheduledDate } from './api'
import Sidebar from './components/Sidebar.vue'
import TaskComposer from './components/TaskComposer.vue'
import TaskRow from './components/TaskRow.vue'
import TaskEditor from './components/TaskEditor.vue'
import DailyReview from './components/DailyReview.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import PeriodPlan from './components/PeriodPlan.vue'
import MemoPage from './components/MemoPage.vue'
import CompletionDialog from './components/CompletionDialog.vue'
import HistoryPage from './components/HistoryPage.vue'
import YesterdayReview from './components/YesterdayReview.vue'
import { IconChevronLeft, IconChevronRight, IconCalendar } from '@tabler/icons-vue'
import './history.css'

// ref 保存页面状态，computed 从状态推导列表。真正的数据保存由 Java 完成。
const today = ref(localDate()), date = ref(today.value), view = ref('today')
const initialWeek = weekRange(today.value)
const periodFrom = ref(initialWeek.from), periodTo = ref(initialWeek.to), periodDays = ref([])
const data = ref({ tasks: [], templates: [], notes: {}, settings: { closeToTray: true, sound: true } })
const revision = ref(0), dayIds = ref([]), paths = ref({}), startup = ref(false), startupAvailable = ref(false)
const loading = ref(true), busy = ref(false), connected = ref(false), error = ref(''), toast = ref(''), exited = ref(false)
const search = ref(''), status = ref('all'), category = ref(''), showFilters = ref(false), composerKey = ref(0)
const editor = ref(null), templateEditor = ref(false), picker = ref(false), chosen = ref([])
const completion = ref(null), completionError = ref('')
const startupReview = ref(null), startupReviewChecking = ref(true), startupReviewChecked = ref(false), startupReviewError = ref('')
const interactionLocked = computed(() => busy.value || Boolean(completion.value) || Boolean(startupReview.value) || startupReviewChecking.value)
const note = ref(''), noteBase = ref(''), noteDirty = ref(false), saving = ref(false)
const memoPage = ref(null)
async function flushMemo() { if (memoPage.value) await memoPage.value.flush() }
let reviewTimer, toastTimer, refreshTimer, heartbeatTimer, readSequence = 0, queue = Promise.resolve()
const dated = computed(() => ['today', 'tomorrow', 'date', 'history'].includes(view.value))
const titles = { week: '本周计划', today: '今天', tomorrow: '明天', date: '每日计划', plans: '全部计划', history: '历史与复盘', templates: '每日模板', settings: '设置与备份' }
const rangeDays = computed(() => {
  const byId = new Map(data.value.tasks.map(task => [task.id, task]))
  return periodDays.value.map(day => ({ date: day.date, tasks: day.taskIds.map(id => byId.get(id)).filter(Boolean) }))
})
const dateText = computed(() => new Date(`${date.value}T12:00:00`).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }))
const dailyTasks = computed(() => dayIds.value.map(id => data.value.tasks.find(t => t.id === id)).filter(Boolean))
const finished = computed(() => dailyTasks.value.filter(t => t.done.includes(date.value)).length)
const progress = computed(() => dailyTasks.value.length ? finished.value / dailyTasks.value.length * 100 : 0)
const categories = computed(() => [...new Set([...data.value.tasks, ...data.value.templates].map(t => t.category))].sort())
const tasks = computed(() => {
  const list = view.value === 'templates' ? data.value.templates : dated.value ? dailyTasks.value : data.value.tasks
  return list.filter(t => (!category.value || t.category === category.value) && (!search.value || `${t.title} ${t.category} ${t.notes}`.toLowerCase().includes(search.value.toLowerCase())) &&
    (!dated.value || status.value === 'all' || (status.value === 'done' ? t.done.includes(date.value) : !t.done.includes(date.value) && !t.deferred.includes(date.value))))
})
function notify(message) { toast.value = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => { toast.value = '' }, 6000) }
function fail(e) { error.value = e.message || '连接失败，请从托盘重新打开。' }
function enqueue(work) { const next = queue.then(work); queue = next.catch(() => {}); return next }
async function checkStartupReview() {
  if (startupReviewChecked.value || exited.value || editor.value || picker.value || completion.value) return
  startupReviewChecking.value = true; startupReviewError.value = ''
  try {
    const review = await request('startup-review', { windowId })
    startupReviewChecked.value = true
    if (review.show) startupReview.value = review
  } catch { startupReviewError.value = '昨日回顾暂时无法读取，可以稍后重试。' }
  finally { startupReviewChecking.value = false }
}
async function openYesterdayHistory() {
  const selected = startupReview.value.date
  await navigate('history', selected)
  if (!error.value && view.value === 'history' && date.value === selected) startupReview.value = null
}

async function load() {
  const sequence = ++readSequence, selected = date.value, scope = `${view.value}/${periodFrom.value}/${periodTo.value}`
  const query = new URLSearchParams({ date: selected })
  if (view.value === 'week') { query.set('from', periodFrom.value); query.set('to', periodTo.value) }
  const result = await request(`state?${query}`)
  if (sequence !== readSequence || selected !== date.value || scope !== `${view.value}/${periodFrom.value}/${periodTo.value}`) return
  data.value = result.data; revision.value = result.revision; today.value = result.today
  dayIds.value = view.value === 'history' ? (result.historyDayTasks || result.dayTasks) : result.dayTasks; paths.value = result.paths; startup.value = result.startup; startupAvailable.value = result.startupAvailable
  if (result.range) periodDays.value = result.range.days
  if (!noteDirty.value) { note.value = data.value.notes[selected] || ''; noteBase.value = note.value }
  connected.value = true; loading.value = false
}
// 每次写操作排队执行，并携带 revision，防止多个窗口静默覆盖。
async function write(fields) {
  try { return await request('action', { ...fields, revision: revision.value }) }
  catch (e) { if (e.status === 409) await load(); throw e }
}
function changeNote(text) {
  note.value = text; noteDirty.value = text !== noteBase.value
  clearTimeout(reviewTimer); reviewTimer = setTimeout(() => flushNote().catch(fail), 700)
}
async function flushNote() {
  clearTimeout(reviewTimer)
  return enqueue(async () => {
    if (!noteDirty.value) return
    const text = note.value, selected = date.value; saving.value = true
    try {
      // 保留其他窗口的复盘，不将本窗口的旧草稿自动覆盖上去。
      await load()
      if ((data.value.notes[selected] || '') !== noteBase.value) throw new Error('这一天的复盘已在其他窗口更新。请先复制当前草稿，再刷新核对。')
      const result = await write({ type: 'note.save', date: selected, text }); revision.value = result.revision
      data.value.notes[selected] = text; noteBase.value = text; noteDirty.value = note.value !== text
    } finally { saving.value = false }
  })
}
async function action(fields, options = {}) {
  if (busy.value || exited.value) return false
  busy.value = true; error.value = ''
  try {
    await flushMemo(); await flushNote()
    await enqueue(async () => {
      await load(); const result = await write(fields); revision.value = result.revision
      await load(); if (!options.quiet) notify(result.path ? `${result.message}：${result.path}` : result.message)
    })
    return true
  } catch (e) { fail(e); return false } finally { busy.value = false }
}
async function navigate(key, selected) {
  if (busy.value) return
  busy.value = true
  try {
    await flushMemo(); await flushNote()
    view.value = key; search.value = ''; status.value = 'all'; category.value = ''; error.value = ''
    if (selected) date.value = selected
    else if (key === 'today') date.value = today.value
    else if (key === 'tomorrow') date.value = shiftDate(today.value, 1)
    else if (key === 'history') date.value = shiftDate(today.value, -1)
    await load()
  } catch (e) { fail(e) } finally { busy.value = false }
}
function chooseDate(value) {
  if (value) navigate(view.value === 'history' ? 'history' : value === today.value ? 'today' : value === shiftDate(today.value, 1) ? 'tomorrow' : 'date', value)
}
async function changePeriod(from, to) {
  if (busy.value) return
  busy.value = true; error.value = ''
  const previous = { from: periodFrom.value, to: periodTo.value }
  try { await flushNote(); periodFrom.value = from; periodTo.value = to; await load() }
  catch (e) { periodFrom.value = previous.from; periodTo.value = previous.to; fail(e) }
  finally { busy.value = false }
}
function newTask(template = false, selected) {
  if (completion.value) return
  templateEditor.value = template
  const start = selected || (view.value === 'week' ? (today.value >= periodFrom.value && today.value <= periodTo.value ? today.value : periodFrom.value) : date.value)
  editor.value = { title: '', category: '生活', notes: '', start, repeat: 'once', time: null, priority: 0, steps: [] }
}
function edit(task) { templateEditor.value = view.value === 'templates'; editor.value = task }
async function saveTask(task) {
  if (task.id && !templateEditor.value && (task.start !== editor.value.start || task.repeat !== editor.value.repeat || (task.repeat === 'weekly' && JSON.stringify(task.weekDays) !== JSON.stringify(editor.value.weekDays))) && !confirm('修改日期或重复规则将影响此计划的所有日期，继续保存？')) return
  if (await action({ type: 'task.save', task, template: templateEditor.value, ...(task.id ? { expected: editor.value } : {}) })) {
    editor.value = null
    if (!task.id) {
      search.value = ''; category.value = ''; status.value = 'all'
      const firstDate = firstScheduledDate(task)
      if (!templateEditor.value && !(view.value === 'week' && firstDate >= periodFrom.value && firstDate <= periodTo.value)) await navigate(firstDate === today.value ? 'today' : 'date', firstDate)
    }
  }
}
async function addTask(task) {
  if (await action({ type: 'task.save', task })) {
    composerKey.value++; search.value = ''; category.value = ''; status.value = 'all'
    const firstDate = firstScheduledDate(task)
    if (firstDate !== task.start) notify(`已添加，下次安排：${firstDate}`)
  }
}
function complete(task, value, selected = date.value) {
  if (busy.value || completion.value) return
  if (value && selected === today.value) {
    completionError.value = ''
    completion.value = { task: { id: task.id, title: task.title }, date: selected, initial: task.completionReports?.[selected] || '', expected: { done: task.done.includes(selected), report: task.completionReports?.[selected] || '' } }
  } else action({ type: 'task.complete', id: task.id, date: selected, value }, { quiet: true })
}
async function saveCompletion(text) {
  if (!completion.value || busy.value) return
  const current = completion.value; completionError.value = ''
  if (await action({ type: current.mode === 'report' ? 'task.report' : 'task.complete', id: current.task.id, date: current.date, value: true, completionReport: text, expectedCompletion: current.expected }, { quiet: true })) completion.value = null
  else completionError.value = error.value
}
function editCompletionReport(task) {
  if (busy.value || completion.value) return
  completionError.value = ''
  completion.value = { mode: 'report', task: { id: task.id, title: task.title }, date: date.value, initial: task.completionReports?.[date.value] || '', expected: { done: task.done.includes(date.value), report: task.completionReports?.[date.value] || '' } }
}
async function finishReview(closeEditor) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await flushNote(); closeEditor() } catch (e) { fail(e) } finally { busy.value = false }
}
function step(task, index, value, selected = date.value) { action({ type: 'task.step', id: task.id, index, date: selected, value }, { quiet: true }) }
function remove(task) {
  if (confirm(`删除“${task.title}”${view.value === 'templates' ? '这个模板' : '整个计划及其完成记录'}？删除前会自动备份。`)) action({ type: 'task.delete', id: task.id, template: view.value === 'templates' })
}
function defer(task, selected = date.value) { if (confirm(`将“${task.title}”顺延到 ${shiftDate(selected, 1)}？`)) action({ type: 'task.defer', id: task.id, date: selected }) }
function apply(ids) { return action({ type: 'template.apply', ids, date: date.value }) }
async function applySelected() { if (await apply(chosen.value)) picker.value = false }
async function native(command, fields = {}) {
  try { await request('native', { command, ...fields }); if (command === 'startup') { await load(); notify('已更新开机启动设置') } }
  catch (e) { fail(e) }
}
async function importFile(file) {
  if (file.size > 16 * 1024 * 1024) { fail(new Error('备份文件不能超过 16 MB。')); return }
  try {
    const imported = JSON.parse(await file.text())
    if (confirm(`将用备份替换当前计划、模板和复盘。${Object.hasOwn(imported, 'memos') ? '备忘录（含回收站）也会替换。' : '此旧备份不含备忘录，现有笔记会保留。'}当前数据会先自动备份，是否继续？`)) await action({ type: 'backup.import', data: imported })
  } catch (e) { fail(new Error(`无法读取备份：${e.message}`)) }
}
async function exit() {
  if (busy.value) return
  try { await flushMemo(); await flushNote(); await request('exit', {}); exited.value = true; connected.value = false; clearInterval(refreshTimer); clearInterval(heartbeatTimer); window.close() }
  catch (e) { fail(e) }
}
function closing() { if (!exited.value) { const memo = memoPage.value?.closingDraft(); windowEvent('closed', { ...(noteDirty.value ? { date: date.value, text: note.value, base: noteBase.value } : {}), ...(memo ? { memo } : {}) }) } }
function shortcut(event) {
  if (!event.ctrlKey || busy.value || editor.value || picker.value || completion.value || startupReview.value || startupReviewChecking.value) return
  if (event.key.toLowerCase() === 'n' && view.value !== 'memos') { event.preventDefault(); newTask() }
  if (event.key.toLowerCase() === 't') { event.preventDefault(); navigate('today') }
}
async function refresh() {
  if (busy.value || saving.value || exited.value || completion.value || startupReview.value || startupReviewChecking.value) return
  try {
    if (view.value === 'today' && !noteDirty.value && date.value !== localDate()) date.value = localDate()
    if (view.value === 'tomorrow' && !noteDirty.value) date.value = shiftDate(localDate(), 1)
    await load()
    if (!startupReviewChecked.value && !startupReviewError.value && !editor.value && !picker.value && view.value === 'today') await checkStartupReview()
  } catch (e) { connected.value = false; fail(e) }
}
onMounted(async () => {
  try { await load(); await checkStartupReview() } catch (e) { loading.value = false; startupReviewChecking.value = false; fail(e) }
  windowEvent('heartbeat'); heartbeatTimer = setInterval(() => windowEvent('heartbeat'), 2000)
  refreshTimer = setInterval(refresh, 20000)
  window.addEventListener('focus', refresh); window.addEventListener('pagehide', closing)
  window.addEventListener('keydown', shortcut)
})
onUnmounted(() => { clearInterval(refreshTimer); clearInterval(heartbeatTimer); clearTimeout(reviewTimer); clearTimeout(toastTimer); window.removeEventListener('focus', refresh); window.removeEventListener('pagehide', closing); window.removeEventListener('keydown', shortcut) })
</script>

<template>
  <div v-if="exited" class="exit-screen"><img src="/icon.svg" alt="" /><h1>已完全退出</h1><p>后台提醒已停止，下次双击程序即可打开。</p></div>
  <div v-else class="app-shell" :aria-busy="busy">
    <Sidebar :inert="Boolean(startupReview) || startupReviewChecking" :view="view" :selected="date" :busy="interactionLocked" @navigate="navigate" @date="chooseDate" @new="newTask()" @folder="native('folder', { name: $event })" @exit="exit" />
    <main :inert="Boolean(startupReview) || startupReviewChecking" :class="['main-content', { 'memo-main': view === 'memos', 'history-main': view === 'history' }]">
      <header v-if="view !== 'memos'" class="page-header"><div><h1>{{ titles[view] }}</h1><p>{{ view === 'week' ? `${periodFrom} — ${periodTo}` : dated ? dateText : view === 'plans' ? '为重要的事，留出位置。' : view === 'templates' ? '把熟悉的节奏，轻松带入每一天。' : '让计划按照你的习惯运行。' }}</p></div>
        <div class="header-actions"><template v-if="view === 'history'"><button class="icon-button" aria-label="上一周复盘" :disabled="interactionLocked" @click="chooseDate(shiftDate(date, -7))"><IconChevronLeft :size="19" /></button><button class="icon-button" aria-label="下一周复盘" :disabled="interactionLocked" @click="chooseDate(shiftDate(date, 7))"><IconChevronRight :size="19" /></button><label class="history-date-picker"><IconCalendar :size="18" /><span>选择日期</span><input type="date" aria-label="复盘日期" :value="date" :disabled="interactionLocked" @change="chooseDate($event.target.value)" /></label></template><template v-else-if="dated"><button class="icon-button" aria-label="前一天" :disabled="interactionLocked" @click="chooseDate(shiftDate(date, -1))">‹</button><button class="icon-button" aria-label="后一天" :disabled="interactionLocked" @click="chooseDate(shiftDate(date, 1))">›</button></template><span v-if="view !== 'history' || !connected" :class="['connection', { offline: !connected }]">●&nbsp; {{ connected ? '本地提醒已就绪' : '连接已断开' }}</span></div>
      </header>
      <div v-if="error" class="error-banner" role="alert">{{ error }}<button class="text-button" @click="refresh">重试连接</button><button class="text-button" @click="error = ''">关闭</button></div>
      <div v-if="startupReviewError" class="error-banner" role="alert">{{ startupReviewError }}<button class="text-button" :disabled="busy || startupReviewChecking" @click="checkStartupReview">重试昨日回顾</button></div>
      <div v-if="loading" class="empty-state">正在读取本地计划…</div>
      <template v-else>
        <MemoPage v-if="view === 'memos'" ref="memoPage" @notify="notify" />
        <SettingsPanel v-else-if="view === 'settings'" :settings="data.settings" :paths="paths" :startup="startup" :startup-available="startupAvailable" :busy="busy" @settings="action({ type: 'settings.save', ...$event })" @startup="native('startup', { enabled: $event })" @reminder="native('reminder')" @export="action({ type: 'backup.export' })" @import="importFile" @folder="native('folder', { name: $event })" />
        <PeriodPlan v-else-if="view === 'week'" :from="periodFrom" :to="periodTo" :today="today" :days="rangeDays" :busy="interactionLocked" @range="changePeriod" @complete="complete" @step="step" @edit="edit" @delete="remove" @defer="defer" @template="action({ type: 'template.create', id: $event.id })" @new="newTask(false, $event)" @date="chooseDate" />
        <HistoryPage v-else-if="view === 'history'" :tasks="dailyTasks" :date="date" :today="today" :busy="interactionLocked" :note="note" :saving="saving" :note-dirty="noteDirty" @date="chooseDate" @note="changeNote" @report="editCompletionReport" @finish-review="finishReview" />
        <template v-else>
          <TaskComposer v-if="dated && view !== 'history'" :date="date" :busy="busy" :reset-key="composerKey" @add="addTask" @templates="chosen = []; picker = true" />
          <div v-if="view === 'templates'" class="intro-row"><p class="muted">模板可包含提醒、分类和子任务，套用时生成单次计划。</p><button class="primary" @click="newTask(true)">＋ 新建模板</button></div>
          <section class="task-section">
            <div class="section-heading"><h2>{{ view === 'templates' ? '我的模板' : view === 'history' ? '当日事项' : dated ? '待办' : '所有计划' }}</h2><div><span v-if="dated" class="muted">{{ finished }} / {{ dailyTasks.length }} 已完成</span><span v-else class="muted">{{ tasks.length }} 项</span><button class="text-button" @click="showFilters = !showFilters">{{ showFilters ? '收起筛选' : '筛选' }}</button></div></div>
            <div v-if="dated" class="progress-track"><div :style="{ width: `${progress}%` }"></div></div><div v-else class="section-rule"></div>
            <div v-if="showFilters" class="filters"><input v-model="search" type="search" aria-label="搜索计划" placeholder="搜索任务、分类、备注…" /><select v-model="category" aria-label="分类筛选"><option value="">全部分类</option><option v-for="name in categories" :key="name">{{ name }}</option></select><select v-if="dated" v-model="status" aria-label="完成状态"><option value="all">全部</option><option value="open">未完成</option><option value="done">已完成</option></select></div>
            <TaskRow v-for="task in tasks" :key="`${date}-${task.id}`" :task="task" :date="date" :dated="dated" :template="view === 'templates'" :busy="interactionLocked" :confirm-complete="date === today" @complete="complete" @step="step" @edit="edit" @delete="remove" @defer="defer" @template="action({ type: 'template.create', id: $event.id })" @apply="apply([$event.id])" />
            <div v-if="!tasks.length" class="empty-state"><div class="empty-mark">✓</div><p>{{ search || status !== 'all' ? '没有符合条件的计划' : view === 'templates' ? '保存常用计划，让每天的开始更轻松。' : '留一点空白，给今天的第一件事。' }}</p></div>
          </section>
          <DailyReview v-if="dated" :key="view === 'history' ? 'history' : 'daily'" :value="note" :date="date" :history="view === 'history'" :saving="saving" @change="changeNote" />
        </template>
      </template>
    </main>
    <YesterdayReview v-if="startupReview" :review="startupReview" :busy="busy" :error="error" @dismiss="startupReview = null" @history="openYesterdayHistory" />
    <TaskEditor v-if="editor" :task="editor" :categories="categories" :template="templateEditor" :busy="busy" @save="saveTask" @cancel="editor = null" />
    <CompletionDialog v-if="completion" :task="completion.task" :date="completion.date" :initial="completion.initial" :mode="completion.mode" :busy="busy" :error="completionError" @save="saveCompletion" @cancel="completion = null" />
    <div v-if="picker" class="modal-backdrop" @click.self="picker = false"><section class="modal template-picker" role="dialog" aria-modal="true" aria-labelledby="picker-title"><div class="modal-heading"><h2 id="picker-title">套用每日模板</h2><button class="icon-button" aria-label="关闭模板选择" @click="picker = false">×</button></div><p class="muted">添加到 {{ date }}，当天已有的同名同分类任务会跳过。</p><label v-for="task in data.templates" :key="task.id" class="template-option"><input v-model="chosen" type="checkbox" :value="task.id" />{{ task.title }}<small>{{ task.category }}</small></label><p v-if="!data.templates.length" class="empty-state">还没有模板。可在任务的“更多”菜单中保存。</p><div class="modal-footer"><button class="secondary" @click="picker = false">取消</button><button class="primary" :disabled="busy || !chosen.length" @click="applySelected">添加到当天</button></div></section></div>
    <div v-if="toast" class="toast" role="status">{{ toast }}</div>
  </div>
</template>
