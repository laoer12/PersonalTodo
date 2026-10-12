<script setup>
import { computed, ref } from 'vue'
import { repeatLabel } from '../api'
const props = defineProps({ task: Object, date: String, dated: Boolean, template: Boolean, busy: Boolean, confirmComplete: Boolean })
const emit = defineEmits(['complete', 'step', 'edit', 'delete', 'defer', 'template', 'apply'])
const expanded = ref(false)
const reportExpanded = ref(false)
const report = computed(() => props.dated ? props.task.completionReports?.[props.date] || '' : '')
function changeCompletion(event) {
  const requested = event.target.checked
  if (requested && props.confirmComplete) event.target.checked = done.value
  emit('complete', props.task, requested)
}
const done = computed(() => props.dated && props.task.done.includes(props.date))
const deferred = computed(() => props.dated && props.task.deferred.includes(props.date))
const priorities = ['普通', '低优先级', '中优先级', '高优先级']
const completedSteps = computed(() => props.task.steps.filter(s => s.done.includes(props.date)).length)
function menu(event, type) { event.target.closest('details').open = false; emit(type, props.task) }
</script>

<template>
  <article :class="['task-row', `priority-${task.priority}`, { 'is-done': done, 'is-deferred': deferred }]" :data-task-id="task.id">
    <input v-if="dated" class="task-check" type="checkbox" :checked="done" :disabled="busy || deferred" :aria-label="`完成 ${task.title}`" @change="changeCompletion" />
    <div class="task-content">
      <div class="task-title">{{ task.title }}</div>
      <div class="task-meta">
        <span v-if="task.time">{{ task.time }}</span><span>{{ template ? '每日模板' : repeatLabel(task) }}</span><span>{{ priorities[task.priority] }}</span><span>{{ task.category }}</span>
        <span v-if="!dated && !template">{{ task.start }}</span>
        <span v-if="task.steps.length">子任务 {{ dated ? `${completedSteps}/` : '' }}{{ task.steps.length }}</span>
        <span v-if="done">已完成</span><span v-if="deferred">已顺延</span>
      </div>
      <div v-if="report && reportExpanded" class="completion-report"><span>{{ date }} · 完成情况</span><p>{{ report }}</p></div>
      <div v-if="expanded" class="task-details">
        <p v-if="task.notes" class="task-notes">{{ task.notes }}</p>
        <label v-for="(step, index) in task.steps" :key="index" class="subtask">
          <input v-if="dated" type="checkbox" :checked="step.done.includes(date)" :disabled="busy || deferred" @change="$emit('step', task, index, $event.target.checked)" />{{ step.title }}
        </label>
      </div>
    </div>
    <div class="task-actions">
      <button v-if="report" class="text-button" :aria-expanded="reportExpanded" @click="reportExpanded = !reportExpanded">{{ reportExpanded ? '收起情况' : '完成情况' }}</button>
      <button v-if="task.steps.length || task.notes" class="text-button" @click="expanded = !expanded">{{ expanded ? '收起' : task.steps.length ? '子任务' : '详情' }}</button>
      <button v-if="template" class="text-button" :disabled="busy" @click="$emit('apply', task)">套用</button>
      <button class="text-button dark" :disabled="busy" @click="$emit('edit', task)">编辑</button>
      <details class="row-menu">
        <summary aria-label="更多操作">更多</summary>
        <div class="menu-popover">
          <button v-if="dated && !done && !deferred" @click="menu($event, 'defer')">顺延到下一天</button>
          <button v-if="!template" @click="menu($event, 'template')">存为每日模板</button>
          <button class="danger" @click="menu($event, 'delete')">删除{{ template ? '模板' : '任务' }}</button>
        </div>
      </details>
    </div>
  </article>
</template>
