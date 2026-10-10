<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { IconSearch, IconPlus, IconPinFilled, IconDots, IconChevronDown, IconArrowLeft, IconTrash, IconFileText } from '@tabler/icons-vue'
import { request } from '../api'
import { renderMarkdown } from '../markdown'

const emit = defineEmits(['notify'])
const memos = ref([]), draft = ref(null), base = ref(null), query = ref(''), trash = ref(false)
const loading = ref(true), operating = ref(false), saving = ref(false), error = ref(''), conflict = ref(false)
const mode = ref('edit'), help = ref(false), composing = ref(false)
let timer, refreshTimer, pendingSave, disposed = false, queue = Promise.resolve()
const dirty = computed(() => draft.value && (draft.value.title.trim() !== (base.value?.title || '') || draft.value.body !== (base.value?.body || '')))
const wordCount = computed(() => [...(draft.value?.body || '')].length)
const preview = computed(() => mode.value === 'preview' ? renderMarkdown(draft.value?.body || '') : '')
const list = computed(() => memos.value.filter(m => Boolean(m.deletedAt) === trash.value && `${m.title}\n${m.body}`.toLowerCase().includes(query.value.toLowerCase()))
  .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt.localeCompare(a.updatedAt) || a.id.localeCompare(b.id)))
const status = computed(() => error.value ? '保存失败' : composing.value || dirty.value ? (saving.value ? '保存中…' : '未保存') : saving.value ? '保存中…' : base.value ? '已保存' : '空白草稿')
function title(m) { return m.title || m.body.split('\n').find(line => line.trim())?.replace(/^#+\s*/, '').slice(0, 40) || '未命名备忘录' }
function summary(m) { return m.body.replace(/[`#*>\[\]]/g, '').replace(/\s+/g, ' ').slice(0, 100) || '还没有正文' }
function timestamp(value, full = false) {
  if (!value) return ''
  const d = new Date(value), pad = n => String(n).padStart(2, '0')
  return full ? `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}` : `${pad(d.getMonth() + 1)}/${pad(d.getDate())}`
}
function setDraft(memo) { draft.value = memo ? { ...memo } : null; base.value = memo ? { ...memo } : null; error.value = ''; conflict.value = false; mode.value = 'edit' }
function updateList(memo) { const index = memos.value.findIndex(m => m.id === memo.id); if (index < 0) memos.value.push(memo); else memos.value[index] = memo }
async function reload() {
  const result = await request('memos')
  if (disposed) return
  memos.value = result.memos
  if (draft.value && !dirty.value && !saving.value && !composing.value) {
    const current = memos.value.find(m => m.id === draft.value.id)
    if (current && Boolean(current.deletedAt) === trash.value) setDraftKeepingMode(current)
    else if (current) setDraft(list.value[0] || null)
    else if (base.value) setDraft(null)
  }
}
function setDraftKeepingMode(memo) { const selectedMode = mode.value; setDraft(memo); mode.value = selectedMode }
function changed() {
  if (!conflict.value) error.value = ''
  clearTimeout(timer)
  if (!composing.value && !conflict.value && !trash.value) timer = setTimeout(() => flush().catch(() => {}), 800)
}
function startComposition() { composing.value = true; clearTimeout(timer) }
function endComposition() { composing.value = false; changed() }
async function flush() {
  clearTimeout(timer)
  if (composing.value) throw new Error('请先完成当前中文输入，再切换或退出。')
  if (pendingSave) await pendingSave
  if (!dirty.value || trash.value) return
  if (conflict.value) throw new Error(error.value || '请先处理备忘录编辑冲突。')
  const work = async () => {
    saving.value = true
    try {
      while (dirty.value) {
        const current = { id: draft.value.id, title: draft.value.title, body: draft.value.body, expectedVersion: base.value?.version || 0 }
        const result = await request('memos', { type: 'save', ...current })
        updateList(result.memo); base.value = { ...result.memo }
        draft.value.version = result.memo.version; draft.value.updatedAt = result.memo.updatedAt; draft.value.pinned = result.memo.pinned
        if (draft.value.title === current.title) draft.value.title = result.memo.title
        error.value = ''; conflict.value = false
      }
    } catch (e) { error.value = e.message; conflict.value = e.status === 409; throw e }
    finally { saving.value = false }
  }
  pendingSave = work()
  try { await pendingSave } finally { pendingSave = null }
}
function perform(work) {
  // Serialize navigation and menu actions; typing stays responsive while saving.
  const next = queue.then(async () => { operating.value = true; try { await work() } catch (e) { error.value = e.message; if (e.status === 409) conflict.value = true } finally { operating.value = false } })
  queue = next.catch(() => {}); return next
}
function choose(memo) { return perform(async () => { await flush(); setDraft(memos.value.find(m => m.id === memo.id) || memo) }) }
function create() { return perform(async () => {
  await flush(); trash.value = false; query.value = ''; setDraft(null)
  draft.value = { id: crypto.randomUUID(), title: '', body: '', pinned: false, version: 0, createdAt: '', updatedAt: '', deletedAt: null }
}) }
function toggleTrash() { return perform(async () => { await flush(); trash.value = !trash.value; query.value = ''; setDraft(list.value[0] || null) }) }
function backToList() { return perform(async () => { await flush(); setDraft(null) }) }
function mutate(type, extra = {}) { return perform(async () => {
  await flush()
  if (!base.value) return
  const id = base.value.id
  const result = await request('memos', { type, id, expectedVersion: base.value.version, ...extra })
  if (result.memo) updateList(result.memo); else memos.value = memos.value.filter(m => m.id !== id)
  if (type === 'delete' || type === 'purge' || type === 'restore') setDraft(list.value[0] || null)
  else if (result.memo) setDraftKeepingMode(result.memo)
  emit('notify', result.path ? `${result.message}：${result.path}` : result.message)
}) }
function removeForever() { if (confirm('永久删除这篇备忘录？回收站中将无法恢复，已有备份仍会保留。')) mutate('purge', { confirmed: true }) }
function saveAsNew() { return perform(async () => {
  if (pendingSave) await pendingSave.catch(() => {})
  const copy = { ...draft.value }; clearTimeout(timer); base.value = null; conflict.value = false; error.value = ''
  draft.value = { ...copy, id: crypto.randomUUID(), title: `${(copy.title || '未命名备忘录').slice(0, 180)}（草稿副本）`, version: 0, pinned: false, deletedAt: null }
  await flush(); await reload(); emit('notify', '已另存为新备忘录，另一窗口的内容也已保留')
}) }
function loadLatest() { return perform(async () => {
  if (!confirm('重新载入会放弃当前未保存草稿。建议先“另存草稿”。继续？')) return
  const id = draft.value.id, result = await request('memos'); memos.value = result.memos
  setDraft(memos.value.find(m => m.id === id && Boolean(m.deletedAt) === trash.value) || null)
}) }
async function retry() {
  try { if (draft.value && dirty.value) await flush(); else { await reload(); error.value = ''; if (!draft.value) setDraft(list.value[0] || null) } }
  catch (e) { error.value = e.message }
}
function closingDraft() {
  if (!dirty.value || trash.value) return undefined
  return { id: draft.value.id, title: draft.value.title, body: draft.value.body, expectedVersion: base.value?.version || 0 }
}
function beforeUnload(event) { if (dirty.value || composing.value) { event.preventDefault(); event.returnValue = '' } }
function shortcut(event) {
  if (!event.ctrlKey) return
  if (event.key.toLowerCase() === 's') { event.preventDefault(); perform(() => flush()) }
  if (event.key.toLowerCase() === 'n') { event.preventDefault(); create() }
}
function clickPreview(event) { const link = event.target.closest('a'); if (link) { event.preventDefault(); if (/^https?:\/\//i.test(link.href)) window.open(link.href, '_blank', 'noopener,noreferrer') } }
onMounted(async () => {
  try { await reload(); if (!draft.value) setDraft(list.value[0] || null) } catch (e) { error.value = e.message }
  finally { loading.value = false }
  refreshTimer = setInterval(() => { if (!operating.value && !saving.value && !dirty.value && !composing.value) reload().catch(() => {}) }, 20000)
  window.addEventListener('beforeunload', beforeUnload); window.addEventListener('keydown', shortcut)
})
onUnmounted(() => { disposed = true; clearTimeout(timer); clearInterval(refreshTimer); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('keydown', shortcut) })
defineExpose({ flush: async () => { await queue; await flush() }, closingDraft, create })
</script>

<template>
  <section :class="['memo-workspace', { 'memo-has-selection': draft }]" aria-label="备忘录工作区">
    <aside class="memo-list-pane">
      <div class="memo-list-header"><h1>{{ trash ? '回收站' : '备忘录' }}</h1><button class="primary memo-new" :disabled="operating || loading" @click="create"><IconPlus :size="16" />新建备忘录</button></div>
      <label class="memo-search"><IconSearch :size="18" :stroke="1.5" /><input v-model="query" type="search" aria-label="搜索备忘录" placeholder="搜索标题或内容" /></label>
      <div class="memo-list" aria-label="笔记列表">
        <p v-if="loading" class="memo-list-empty">正在读取备忘录…</p>
        <button v-for="memo in list" :key="memo.id" :class="['memo-list-item', { selected: draft?.id === memo.id }]" :disabled="operating" :aria-pressed="draft?.id === memo.id" @click="choose(memo)">
          <span class="memo-list-title">{{ title(memo) }}<IconPinFilled v-if="memo.pinned" :size="16" aria-label="已置顶" /></span><span class="memo-list-summary">{{ summary(memo) }}</span><time>{{ timestamp(trash ? memo.deletedAt : memo.updatedAt) }}</time>
        </button>
        <p v-if="!loading && !list.length" class="memo-list-empty">{{ query ? '没有匹配的备忘录' : trash ? '回收站是空的' : '还没有备忘录，记录第一点想法吧。' }}</p>
      </div>
      <button class="memo-trash-link text-button" :disabled="operating" @click="toggleTrash"><IconArrowLeft v-if="trash" :size="15" /><IconTrash v-else :size="15" />{{ trash ? '返回备忘录' : '回收站' }}</button>
    </aside>
    <div class="memo-editor-pane">
      <div v-if="error" class="memo-error" role="alert"><p>{{ error }}</p><div><button v-if="!conflict" class="text-button dark" @click="retry">重试保存</button><button v-if="draft && dirty" class="text-button dark" @click="saveAsNew">另存草稿</button><button v-if="conflict" class="text-button dark" @click="loadLatest">重新载入</button></div></div>
      <template v-if="draft">
        <button class="memo-mobile-back text-button" :disabled="operating" @click="backToList"><IconArrowLeft :size="16" />笔记列表</button>
        <header class="memo-editor-heading">
          <div class="memo-heading-content"><input v-model="draft.title" class="memo-title-input" aria-label="备忘录标题" placeholder="未命名备忘录" maxlength="200" :readonly="trash || operating" @input="changed" @compositionstart="startComposition" @compositionend="endComposition" /><p>{{ base ? `${trash ? '删除于' : '更新于'} ${timestamp(trash ? base.deletedAt : base.updatedAt, true)}` : '记录想法，自动保存' }}</p></div>
          <details class="row-menu memo-menu"><summary aria-label="备忘录更多操作"><IconDots :size="16" />更多<IconChevronDown :size="12" /></summary><div class="menu-popover">
            <template v-if="!trash"><button :disabled="operating || !base" @click="mutate('pin', { value: !draft.pinned })">{{ draft.pinned ? '取消置顶' : '置顶备忘录' }}</button><button :disabled="operating || !base" @click="mutate('export', { format: 'md' })">导出 Markdown</button><button :disabled="operating || !base" @click="mutate('export', { format: 'txt' })">导出 TXT</button><button class="danger" :disabled="operating || !base" @click="mutate('delete')">移入回收站</button></template>
            <template v-else><button :disabled="operating" @click="mutate('restore')">恢复备忘录</button><button class="danger" :disabled="operating" @click="removeForever">永久删除</button></template>
          </div></details>
        </header>
        <div class="memo-editor-tools"><div class="memo-mode" role="group" aria-label="编辑模式"><button :class="{ active: mode === 'edit' }" :aria-pressed="mode === 'edit'" @click="mode = 'edit'">编辑</button><button :class="{ active: mode === 'preview' }" :aria-pressed="mode === 'preview'" @click="mode = 'preview'">预览</button></div><button class="text-button" :aria-expanded="help" @click="help = !help">Markdown 语法</button></div>
        <div v-if="help" class="memo-markdown-help"><code># 一级标题</code><code>## 二级标题</code><code>**加粗**</code><code>- 列表</code><code>[文字](链接)</code><code>``` 代码块</code><span>正文支持 Markdown，点击“预览”查看排版。</span></div>
        <textarea v-if="mode === 'edit'" v-model="draft.body" class="memo-body-input" aria-label="备忘录正文" placeholder="写下你的想法…&#10;&#10;支持 Markdown 标题、列表和代码块。" maxlength="200000" :readonly="trash || operating" spellcheck="false" @input="changed" @compositionstart="startComposition" @compositionend="endComposition"></textarea>
        <div v-else class="memo-preview markdown-body" aria-label="Markdown 预览" @click="clickPreview"><p v-if="!draft.body" class="muted">写点内容后，就可以在这里预览。</p><div v-else v-html="preview"></div></div>
        <footer class="memo-save-footer" aria-live="polite"><span :class="{ 'memo-save-error': error }">{{ trash ? '回收站中的备忘录只读' : status }}<template v-if="!trash && base && !dirty && !error"> · {{ timestamp(base.updatedAt, true) }}</template></span><span>{{ wordCount.toLocaleString() }} 字</span></footer>
      </template>
      <div v-else class="memo-editor-empty"><IconFileText :size="34" :stroke="1.2" /><h2>{{ trash ? '回收站' : '给想法留一个地方' }}</h2><p>{{ trash ? '删除的备忘录会留在这里，可以随时恢复。' : '选择一篇备忘录，或新建一篇开始记录。' }}</p><button v-if="!trash" class="primary" :disabled="loading || operating" @click="create"><IconPlus :size="16" />新建备忘录</button></div>
    </div>
  </section>
</template>
