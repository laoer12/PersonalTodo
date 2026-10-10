<script setup>
import { reactive, computed, onMounted, onUnmounted, ref } from 'vue'
const props = defineProps({ task: Object, date: String, template: Boolean, busy: Boolean })
const emit = defineEmits(['save', 'cancel'])
const firstInput = ref(null)
const form = reactive({ id: props.task?.id, title: props.task?.title || '', start: props.task?.start || props.date,
  category: props.task?.category || '生活', priority: props.task?.priority || 0, repeat: props.task?.repeat || 'once',
  remind: !!props.task?.time, time: props.task?.time || '09:00', notes: props.task?.notes || '', steps: (props.task?.steps || []).map(s => s.title).join('\n') })
const heading = computed(() => `${props.task?.id ? '编辑' : '新建'}${props.template ? '模板' : '待办'}`)
function submit() {
  const steps = form.steps.split('\n').map(s => s.trim()).filter(Boolean)
  if (steps.length > 50 || steps.some(s => s.length > 200)) { alert('最多 50 个子任务，每项最多 200 字。'); return }
  emit('save', { id: form.id, title: form.title.trim(), start: form.start, category: form.category.trim() || '生活', priority: form.priority,
    repeat: props.template ? 'once' : form.repeat, time: form.remind ? form.time : null, notes: form.notes, steps })
}
function escape(e) { if (e.key === 'Escape' && !props.busy) emit('cancel') }
onMounted(() => { firstInput.value.focus(); window.addEventListener('keydown', escape) })
onUnmounted(() => window.removeEventListener('keydown', escape))
</script>

<template>
  <div class="modal-backdrop" @click.self="!busy && $emit('cancel')">
    <section class="modal" role="dialog" aria-modal="true" :aria-label="heading">
      <h2>{{ heading }}</h2><p class="muted">{{ template ? '保存常用安排，需要时套用。' : '安排好时间，一件件完成。' }}</p>
      <form @submit.prevent="submit">
        <label class="form-field">任务名称<input ref="firstInput" v-model="form.title" aria-label="任务名称" maxlength="160" required /></label>
        <div class="form-grid">
          <label class="form-field">分类<input v-model="form.category" list="categories" maxlength="40" /><datalist id="categories"><option>工作</option><option>学习</option><option>生活</option><option>健康</option></datalist></label>
          <label class="form-field">优先级<select v-model.number="form.priority"><option :value="0">普通</option><option :value="1">低优先级</option><option :value="2">中优先级</option><option :value="3">高优先级</option></select></label>
          <label class="form-field">开始日期<input v-model="form.start" type="date" required :disabled="template" /></label>
          <label class="form-field">重复规则<select v-model="form.repeat" :disabled="template"><option value="once">仅此一天</option><option value="daily">每天</option><option value="weekdays">工作日</option><option value="weekly">每周</option><option value="monthly">每月</option></select></label>
        </div>
        <div class="editor-reminder"><label class="inline-check"><input v-model="form.remind" type="checkbox" />到点提醒</label><input v-model="form.time" type="time" :disabled="!form.remind" :required="form.remind" /></div>
        <label class="form-field">子任务 · 每行一项<textarea v-model="form.steps" aria-label="编辑子任务" rows="3" /></label>
        <label class="form-field">备注<textarea v-model="form.notes" rows="3" maxlength="10000" /></label>
        <p v-if="task?.id && !template" class="field-tip">修改日期或重复规则会影响整个计划，已有完成记录会保留。</p>
        <div class="modal-actions"><button class="secondary" type="button" :disabled="busy" @click="$emit('cancel')">取消</button><button class="primary" type="submit" :disabled="busy || !form.title.trim()">保存{{ template ? '模板' : '待办' }}</button></div>
      </form>
    </section>
  </div>
</template>
