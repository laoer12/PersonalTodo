<script setup>
import { reactive, computed, onMounted, onUnmounted, ref, watch, nextTick } from 'vue'
import { IconX } from '@tabler/icons-vue'
import SelectField from './SelectField.vue'
const props = defineProps({ task: Object, date: String, template: Boolean, busy: Boolean, categories: { type: Array, default: () => [] } })
const emit = defineEmits(['save', 'cancel'])
const firstInput = ref(null)
const form = reactive({ id: props.task?.id, title: props.task?.title || '', start: props.task?.start || props.date,
  category: props.task?.category || '生活', priority: props.task?.priority || 0, repeat: props.task?.repeat || 'once',
  remind: !!props.task?.time, time: props.task?.time || '09:00', notes: props.task?.notes || '', steps: (props.task?.steps || []).map(s => s.title).join('\n') })
const heading = computed(() => `${props.task?.id ? '编辑' : '新建'}${props.template ? '模板' : '待办'}`)
const categoryChoice = ref(form.category), customCategory = ref(null)
const categoryOptions = computed(() => [...new Set(['生活', '学习', '工作', '健康', '个人', ...props.categories, props.task?.category].filter(Boolean))].map(value => ({ value, label: value })).concat({ value: '', label: '自定义分类…' }))
watch(categoryChoice, async value => {
  form.category = value
  if (!value) { await nextTick(); customCategory.value?.focus() }
})
function submit() {
  const steps = form.steps.split('\n').map(s => s.trim()).filter(Boolean)
  if (steps.length > 50 || steps.some(s => s.length > 200)) { alert('最多 50 个子任务，每项最多 200 字。'); return }
  emit('save', { id: form.id, title: form.title.trim(), start: form.start, category: form.category.trim() || '生活', priority: form.priority,
    repeat: props.template ? 'once' : form.repeat, time: form.remind ? form.time : null, notes: form.notes, steps })
}
function escape(e) { if (e.key === 'Escape' && !e.defaultPrevented && !e.isComposing && !props.busy) emit('cancel') }
onMounted(() => { firstInput.value.focus(); window.addEventListener('keydown', escape) })
onUnmounted(() => window.removeEventListener('keydown', escape))
</script>

<template>
  <div class="modal-backdrop" @click.self="!busy && $emit('cancel')">
    <section class="modal task-editor" role="dialog" aria-modal="true" :aria-label="heading">
      <div class="task-editor-heading"><h2>{{ heading }}</h2><button class="icon-button" type="button" aria-label="关闭待办编辑" :disabled="busy" @click="emit('cancel')"><IconX :size="17" /></button></div>
      <form @submit.prevent="submit">
        <label class="form-field">任务名称<input ref="firstInput" v-model="form.title" aria-label="任务名称" maxlength="160" required :disabled="busy" placeholder="想完成的一件事" /></label>
        <div class="form-grid">
          <div class="form-field category-field"><span>分类</span><SelectField v-model="categoryChoice" label="分类" :options="categoryOptions" :disabled="busy" /><input v-if="categoryChoice === ''" ref="customCategory" v-model="form.category" aria-label="自定义分类" maxlength="40" :disabled="busy" required placeholder="输入分类名称" /></div>
          <label class="form-field">优先级<select v-model.number="form.priority" :disabled="busy"><option :value="0">普通</option><option :value="1">低优先级</option><option :value="2">中优先级</option><option :value="3">高优先级</option></select></label>
          <label class="form-field">开始日期<input v-model="form.start" type="date" required :disabled="template || busy" /></label>
          <label class="form-field">重复规则<select v-model="form.repeat" :disabled="template || busy"><option value="once">仅此一天</option><option value="daily">每天</option><option value="weekdays">工作日</option><option value="weekly">每周</option><option value="monthly">每月</option></select></label>
        </div>
        <div class="editor-reminder"><label class="inline-check"><input v-model="form.remind" type="checkbox" :disabled="busy" />到点提醒</label><input v-model="form.time" type="time" aria-label="待办提醒时间" :disabled="!form.remind || busy" :required="form.remind" /></div>
        <div class="task-editor-secondary-fields"><label class="form-field">子任务 · 每行一项<textarea v-model="form.steps" aria-label="编辑子任务" rows="3" :disabled="busy" placeholder="可选，每行填写一个子任务" /></label>
        <label class="form-field">备注<textarea v-model="form.notes" rows="3" maxlength="10000" :disabled="busy" placeholder="可选，补充计划说明" /></label></div>
        <p v-if="task?.id && !template" class="field-tip">修改日期或重复规则会影响整个计划，已有完成记录会保留。</p>
        <div class="modal-actions"><button class="secondary" type="button" :disabled="busy" @click="$emit('cancel')">取消</button><button class="primary" type="submit" :disabled="busy || !form.title.trim()">保存{{ template ? '模板' : '待办' }}</button></div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.task-editor { width: 620px; padding: 22px 26px; }
.task-editor-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.task-editor-heading h2 { font-size: 22px; }
.task-editor .form-field { display: flex; flex-direction: column; gap: 7px; margin: 10px 0; font-size: 13px; color: #777780; min-width: 0; }
.task-editor .form-grid { gap: 0 20px; }
.task-editor .form-grid .form-field { margin: 0 0 12px; }
.task-editor-secondary-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 20px; }
.task-editor .form-field input, .task-editor .form-field select, .task-editor .form-field textarea { width: 100%; padding: 9px 12px; font-size: 14px; color: #29292d; border-color: #e1e1e6; }
.task-editor .form-field input, .task-editor .form-field select { min-height: 40px; }
.task-editor .form-field textarea { line-height: 1.7; }
.task-editor .category-field :deep(.select-field) { min-width: 0; width: 100%; }
.task-editor .category-field :deep(.select-menu) { width: 100%; }
.task-editor .editor-reminder { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; margin: 2px 0 12px; padding: 10px 14px; border-radius: 10px; background: #f7f7f8; }
.task-editor .editor-reminder .inline-check { display: inline-flex; flex-direction: row; align-items: center; flex: 0 0 auto; gap: 8px; margin: 0; width: auto; white-space: nowrap; font-size: 14px; color: #515159; }
.task-editor .editor-reminder input[type=checkbox] { width: 18px; height: 18px; padding: 0; margin: 0; flex: 0 0 18px; }
.task-editor .editor-reminder input[type=time] { width: 132px; flex: 0 0 132px; height: 40px; padding: 7px 11px; font-size: 14px; }
.task-editor .editor-reminder input[type=time]:disabled { background: #fff; opacity: .55; }
.task-editor .modal-actions { margin-top: 20px; padding-top: 16px; border-top: 1px solid #ededf0; }
@media(max-width:700px) {
 .task-editor { padding: 20px; }
 .task-editor .form-grid, .task-editor-secondary-fields { grid-template-columns: minmax(0, 1fr); }
}
</style>
