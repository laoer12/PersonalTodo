<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { IconCircleCheckFilled, IconCircle, IconArrowRight, IconArrowLeft, IconChevronUp, IconChevronDown, IconPencil } from '@tabler/icons-vue'
import { shiftDate, weekRange, repeatLabel } from '../api'

const props = defineProps({ tasks: Array, date: String, today: String, busy: Boolean, note: String, saving: Boolean, noteDirty: Boolean })
const emit = defineEmits(['date', 'note', 'report', 'finish-review'])
const selectedId = ref(''), collapsedDone = ref(false), collapsedOpen = ref(false), editingReview = ref(false), reviewField = ref(null)
const detailPane = ref(null), listPane = ref(null)
const isDone = task => task.done.includes(props.date)
const isDeferred = task => !isDone(task) && task.deferred.includes(props.date)
const doneTasks = computed(() => props.tasks.filter(isDone))
const otherTasks = computed(() => props.tasks.filter(task => !isDone(task)))
const deferredCount = computed(() => props.tasks.filter(isDeferred).length)
const openCount = computed(() => otherTasks.value.length - deferredCount.value)
const progress = computed(() => props.tasks.length ? Math.round(doneTasks.value.length / props.tasks.length * 100) : 0)
const selectedTask = computed(() => props.tasks.find(task => task.id === selectedId.value))
const report = computed(() => selectedTask.value?.completionReports?.[props.date] || '')
const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const weekDays = computed(() => Array.from({ length: 7 }, (_, offset) => {
  const date = shiftDate(weekRange(props.date).from, offset)
  return { date, label: date.slice(5).replace('-', '/'), weekday: weekdays[new Date(`${date}T12:00:00`).getDay()] }
}))
const dayLabel = computed(() => `${Number(props.date.slice(5, 7))}月${Number(props.date.slice(8))}日`)
watch(() => props.date, () => { selectedId.value = ''; editingReview.value = false; collapsedDone.value = false; collapsedOpen.value = false })
watch([() => props.date, () => props.tasks], () => {
  if (!props.tasks.some(task => task.id === selectedId.value)) selectedId.value = doneTasks.value[0]?.id || otherTasks.value[0]?.id || ''
}, { immediate: true })
async function editReview() {
  editingReview.value = true
  await nextTick(); reviewField.value?.focus()
}
async function chooseTask(task) {
  selectedId.value = task.id
  await nextTick()
  if (window.matchMedia('(max-width: 850px)').matches) detailPane.value?.scrollIntoView({ block: 'start' })
}
</script>

<template>
  <section class="history-page" aria-label="历史记录">
    <nav class="history-week" aria-label="复盘周日期">
      <button v-for="day in weekDays" :key="day.date" :data-date="day.date" :class="['history-day', { active: day.date === date }]"
        :aria-label="`查看 ${day.date} ${day.weekday}复盘`" :aria-current="day.date === date ? 'date' : undefined" :disabled="busy" @click="emit('date', day.date)">
        <strong>{{ day.label }}</strong><span>{{ day.weekday }}</span><small v-if="day.date === today">今天</small>
      </button>
    </nav>
    <div class="history-summary" aria-label="当日完成统计">
      <div class="history-counts"><span><strong>{{ tasks.length }}</strong> 项计划</span><span><strong>{{ doneTasks.length }}</strong> 已完成</span><span><strong>{{ openCount }}</strong> 未完成</span><span><strong>{{ deferredCount }}</strong> 已顺延</span></div>
      <div class="history-progress"><div class="history-progress-track" role="progressbar" aria-label="当日完成率" :aria-valuenow="progress" aria-valuemin="0" aria-valuemax="100"><span :style="{ width: `${progress}%` }"></span></div><span>{{ tasks.length ? `${progress}%` : '—' }}</span></div>
    </div>
    <div class="history-workspace">
      <div ref="listPane" class="history-list" aria-label="当日事项">
        <section class="history-group">
          <button class="history-group-heading" :aria-expanded="!collapsedDone" @click="collapsedDone = !collapsedDone"><span>已完成 <small>{{ doneTasks.length }}</small></span><component :is="collapsedDone ? IconChevronDown : IconChevronUp" :size="17" /></button>
          <template v-if="!collapsedDone">
            <button v-for="task in doneTasks" :key="task.id" :class="['history-task', { selected: selectedId === task.id }]" :data-task-id="task.id" :aria-pressed="selectedId === task.id" :aria-label="`查看事项 ${task.title}`" @click="chooseTask(task)">
              <IconCircleCheckFilled class="history-state-icon" :size="24" /><span class="history-task-copy"><strong>{{ task.title }}</strong><span>{{ dayLabel }} · {{ task.category }}</span><small v-if="!task.completionReports?.[date]">未填写完成情况</small></span>
            </button>
            <p v-if="!doneTasks.length" class="history-list-empty">当天还没有已完成事项</p>
          </template>
        </section>
        <section class="history-group">
          <button class="history-group-heading" :aria-expanded="!collapsedOpen" @click="collapsedOpen = !collapsedOpen"><span>未完成与已顺延 <small>{{ otherTasks.length }}</small></span><component :is="collapsedOpen ? IconChevronDown : IconChevronUp" :size="17" /></button>
          <template v-if="!collapsedOpen">
            <button v-for="task in otherTasks" :key="task.id" :class="['history-task', { selected: selectedId === task.id }]" :data-task-id="task.id" :aria-pressed="selectedId === task.id" :aria-label="`查看事项 ${task.title}`" @click="chooseTask(task)">
              <component :is="isDeferred(task) ? IconArrowRight : IconCircle" class="history-state-icon open" :size="24" /><span class="history-task-copy"><strong>{{ task.title }}</strong><span>{{ dayLabel }} · {{ task.category }}</span></span><small class="history-status-tag">{{ isDeferred(task) ? '已顺延' : '未完成' }}</small>
            </button>
            <p v-if="!otherTasks.length" class="history-list-empty">{{ tasks.length ? '当天事项已全部完成' : '这一天暂无事项' }}</p>
          </template>
        </section>
      </div>
      <div ref="detailPane" class="history-detail">
        <button class="history-mobile-back text-button" @click="listPane?.scrollIntoView({ block: 'start' })"><IconArrowLeft :size="16" />返回事项列表</button>
        <template v-if="selectedTask">
          <div class="history-detail-heading"><h2>{{ selectedTask.title }}</h2><p><component :is="isDone(selectedTask) ? IconCircleCheckFilled : isDeferred(selectedTask) ? IconArrowRight : IconCircle" :size="21" /><span>{{ isDone(selectedTask) ? '已完成' : isDeferred(selectedTask) ? `已顺延至 ${shiftDate(date, 1)}` : '未完成' }}</span><span class="muted">{{ dayLabel }} · {{ selectedTask.category }}</span></p></div>
          <section class="history-report"><div class="history-detail-section-heading"><h3>{{ isDone(selectedTask) ? '完成情况' : report ? '上次填写的情况' : '事项情况' }}</h3><button v-if="isDone(selectedTask)" class="secondary" :disabled="busy" @click="emit('report', selectedTask)"><IconPencil :size="15" />{{ report ? '修改完成情况' : '补充完成情况' }}</button></div><p v-if="report" class="history-report-text" tabindex="0">{{ report }}</p><p v-else class="history-placeholder">{{ isDone(selectedTask) ? '未填写完成情况。' : isDeferred(selectedTask) ? '该事项已顺延到下一天。' : '该事项当天未完成。' }}</p></section>
          <details v-if="selectedTask.notes || selectedTask.steps.length" class="history-plan-details"><summary>计划详情 <span>{{ repeatLabel(selectedTask) }}<template v-if="selectedTask.time"> · {{ selectedTask.time }}</template></span></summary><p v-if="selectedTask.notes">{{ selectedTask.notes }}</p><ul v-if="selectedTask.steps.length"><li v-for="(step, index) in selectedTask.steps" :key="index"><component :is="step.done.includes(date) ? IconCircleCheckFilled : IconCircle" :size="16" />{{ step.title }}</li></ul></details>
        </template>
        <div v-else class="history-detail-empty"><h2>这一天暂无事项</h2><p>仍然可以记录当天的复盘。</p></div>
        <section class="history-review">
          <div class="history-detail-section-heading"><h3>当日复盘</h3><button v-if="!editingReview" class="secondary" :disabled="busy" @click="editReview"><IconPencil :size="15" />编辑复盘</button><button v-else class="secondary" :disabled="busy || saving" @click="emit('finish-review', () => { editingReview = false })">{{ saving ? '正在保存…' : '完成编辑' }}</button></div>
          <template v-if="editingReview"><textarea ref="reviewField" :value="note" class="history-review-input" aria-label="当日复盘" maxlength="10000" rows="6" :disabled="busy" placeholder="今天有什么进展？遇到了什么问题？下一步想调整什么？" @input="emit('note', $event.target.value)"></textarea><small v-if="saving || noteDirty" class="history-saving" role="status">{{ saving ? '正在保存…' : '待保存' }}</small></template>
          <p v-else :class="['history-review-text', { 'history-placeholder': !note }]">{{ note || '还没有写下当天的复盘。' }}</p>
        </section>
      </div>
    </div>
  </section>
</template>
