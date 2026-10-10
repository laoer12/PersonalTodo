<script setup>
import { computed, ref, watch } from 'vue'
import { weekRange, shiftDate } from '../api'
import TaskRow from './TaskRow.vue'

const props = defineProps({ from: String, to: String, today: String, days: Array, busy: Boolean })
const emit = defineEmits(['range', 'complete', 'step', 'edit', 'delete', 'defer', 'template', 'new', 'date'])
const draftFrom = ref(props.from), draftTo = ref(props.to), validation = ref('')
const search = ref(''), status = ref('all'), category = ref(''), filters = ref(false)
watch(() => [props.from, props.to], ([from, to]) => { draftFrom.value = from; draftTo.value = to; validation.value = '' })
const total = computed(() => props.days.reduce((sum, day) => sum + day.tasks.length, 0))
const finished = computed(() => props.days.reduce((sum, day) => sum + day.tasks.filter(task => task.done.includes(day.date)).length, 0))
const categories = computed(() => [...new Set(props.days.flatMap(day => day.tasks.map(task => task.category)))].sort())
const visibleDays = computed(() => props.days.map(day => ({ ...day, visible: day.tasks.filter(task =>
  (!search.value || `${task.title} ${task.category} ${task.notes}`.toLowerCase().includes(search.value.toLowerCase())) &&
  (!category.value || task.category === category.value) &&
  (status.value === 'all' || (status.value === 'done' ? task.done.includes(day.date) : !task.done.includes(day.date) && !task.deferred.includes(day.date)))
)})).filter(day => !(search.value || category.value || status.value !== 'all') || day.visible.length))
function choose(from, to) {
  if (!from || !to) { validation.value = '请选择开始和结束日期。'; return }
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000 + 1
  if (!Number.isFinite(days) || days < 1 || days > 366) { validation.value = '结束日期不能早于开始日期，一个周期最多显示 366 天。'; return }
  validation.value = ''; emit('range', from, to)
}
function preset(key) {
  const current = weekRange(props.today)
  if (key === 'next') choose(shiftDate(current.from, 7), shiftDate(current.to, 7))
  else if (key === 'month') {
    const date = new Date(`${props.today}T12:00:00`)
    const end = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
    choose(`${props.today.slice(0, 8)}01`, `${props.today.slice(0, 8)}${end}`)
  } else choose(current.from, current.to)
}
function move(direction) {
  const length = (Date.parse(`${props.to}T00:00:00Z`) - Date.parse(`${props.from}T00:00:00Z`)) / 86400000 + 1
  choose(shiftDate(props.from, length * direction), shiftDate(props.to, length * direction))
}
function heading(date) { return new Date(`${date}T12:00:00`).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }) }
</script>

<template>
  <section class="period-plan">
    <div class="period-presets"><button class="secondary" :disabled="busy" @click="preset('week')">本周</button><button class="text-button" :disabled="busy" @click="preset('next')">下周</button><button class="text-button" :disabled="busy" @click="preset('month')">本月</button><span class="muted">或自选时间周期</span></div>
    <form class="period-range" @submit.prevent="choose(draftFrom, draftTo)">
      <label>开始日期<input v-model="draftFrom" aria-label="周期开始日期" type="date" required :disabled="busy" /></label><span class="range-separator">至</span><label>结束日期<input v-model="draftTo" aria-label="周期结束日期" type="date" required :disabled="busy" /></label><button class="primary" type="submit" :disabled="busy">查看计划</button>
    </form>
    <p v-if="validation" class="period-validation" role="alert">{{ validation }}</p>
    <div class="section-heading period-heading"><div><h2>周期安排</h2><span class="muted">{{ finished }} / {{ total }} 已完成</span></div><div><button class="icon-button" aria-label="上一个周期" :disabled="busy" @click="move(-1)">‹</button><button class="icon-button" aria-label="下一个周期" :disabled="busy" @click="move(1)">›</button><button class="text-button" @click="filters = !filters">{{ filters ? '收起筛选' : '筛选' }}</button></div></div>
    <div class="progress-track"><div :style="{ width: `${total ? finished / total * 100 : 0}%` }"></div></div>
    <div v-if="filters" class="filters period-filters"><input v-model="search" type="search" aria-label="搜索周期计划" placeholder="搜索任务、分类、备注…" /><select v-model="category" aria-label="周期分类筛选"><option value="">全部分类</option><option v-for="name in categories" :key="name">{{ name }}</option></select><select v-model="status" aria-label="周期完成状态"><option value="all">全部</option><option value="open">未完成</option><option value="done">已完成</option></select></div>
    <section v-for="day in visibleDays" :key="day.date" class="period-day" :data-date="day.date">
      <div class="period-day-heading"><button class="day-label" @click="$emit('date', day.date)">{{ heading(day.date) }}<span v-if="day.date === today" class="today-mark">今天</span></button><div><span class="muted">{{ day.tasks.filter(task => task.done.includes(day.date)).length }} / {{ day.tasks.length }}</span><button class="text-button" :disabled="busy" @click="$emit('new', day.date)">＋ 添加待办</button></div></div>
      <TaskRow v-for="task in day.visible" :key="task.id" :task="task" :date="day.date" dated :busy="busy" :confirm-complete="day.date === today" @complete="(task, value) => emit('complete', task, value, day.date)" @step="(task, index, value) => emit('step', task, index, value, day.date)" @edit="emit('edit', $event)" @delete="emit('delete', $event)" @defer="emit('defer', $event, day.date)" @template="emit('template', $event)" />
      <p v-if="!day.visible.length" class="period-empty">这一天还没有安排</p>
    </section>
    <p v-if="!visibleDays.length" class="empty-state">这个周期内没有符合条件的计划</p>
  </section>
</template>
