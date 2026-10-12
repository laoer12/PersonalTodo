<script setup>
import { reactive, ref, watch } from 'vue'
import SelectField from './SelectField.vue'
import WeekdayPicker from './WeekdayPicker.vue'
import { weekdayOf } from '../api'
const props = defineProps({ busy: Boolean, resetKey: Number, date: String })
const emit = defineEmits(['add', 'templates'])
const form = reactive({ title: '', remind: false, time: '09:00', repeat: 'once', priority: 0 })
const stepLines = ref(''), showSteps = ref(false)
const weekDays = ref([weekdayOf(props.date)]), weekDaysTouched = ref(false)
function chooseWeekDays(value) { weekDays.value = value; weekDaysTouched.value = true }
watch(() => props.date, value => { if (!weekDaysTouched.value) weekDays.value = [weekdayOf(value)] })
const repeatOptions = [{ value: 'once', label: '单次' }, { value: 'daily', label: '每天' }, { value: 'weekdays', label: '工作日' }, { value: 'weekly', label: '每周' }, { value: 'monthly', label: '每月' }]
const priorityOptions = [{ value: 0, label: '无' }, { value: 1, label: '低' }, { value: 2, label: '中' }, { value: 3, label: '高' }]
watch(() => props.resetKey, () => { form.title = ''; form.remind = false; form.repeat = 'once'; form.priority = 0; stepLines.value = ''; showSteps.value = false; weekDaysTouched.value = false; weekDays.value = [weekdayOf(props.date)] })
function submit() {
  if (!form.title.trim() || (form.repeat === 'weekly' && !weekDays.value.length)) return
  emit('add', { title: form.title.trim(), start: props.date, repeat: form.repeat, priority: form.priority, time: form.remind ? form.time : null,
    weekDays: form.repeat === 'weekly' ? [...weekDays.value] : [], category: '生活', notes: '', steps: stepLines.value.split('\n').map(s => s.trim()).filter(Boolean) })
}
</script>

<template>
  <form class="composer" @submit.prevent="submit">
    <input v-model="form.title" class="composer-title" aria-label="添加一个待办" placeholder="添加一个待办…" maxlength="160" :disabled="busy" />
    <div class="composer-options">
      <div class="composer-field reminder-field"><label class="inline-check"><input v-model="form.remind" type="checkbox" :disabled="busy" />提醒</label>
        <input v-model="form.time" type="time" aria-label="提醒时间" :disabled="!form.remind || busy" required /></div>
      <div class="composer-field"><span>重复</span><SelectField v-model="form.repeat" label="重复规则" :options="repeatOptions" :disabled="busy" /></div>
      <div class="composer-field"><span>优先级</span><SelectField v-model="form.priority" label="优先级" :options="priorityOptions" :disabled="busy" /></div>
    </div>
    <WeekdayPicker v-if="form.repeat === 'weekly'" :model-value="weekDays" :disabled="busy" @update:model-value="chooseWeekDays" />
    <textarea v-if="showSteps" v-model="stepLines" class="composer-steps" aria-label="新增子任务" placeholder="子任务，每行一项" rows="3" :disabled="busy" />
    <div class="composer-bottom">
      <button class="secondary" type="button" :disabled="busy" @click="showSteps = !showSteps">子任务 · {{ stepLines.split('\n').filter(s => s.trim()).length }}</button>
      <div><button class="text-button" type="button" :disabled="busy" @click="$emit('templates')">套用模板</button><button class="primary" type="submit" :disabled="busy || !form.title.trim() || (form.repeat === 'weekly' && !weekDays.length)">添加</button></div>
    </div>
  </form>
</template>

<style scoped>
.composer { padding: 21px 23px 17px; border-color: #dedee3; border-radius: 17px; background: #fff; box-shadow: 0 2px 6px #22222603; }
.composer:focus-within { border-color: #b3b3bb; }
.composer-title { padding: 1px 0 19px; font-size: 16px; line-height: 1.7; }
.composer-title:focus-visible { outline: none; }
.composer-options { border-top: 1px solid #f0f0f2; padding: 17px 0 12px; gap: 20px; }
.composer-field { display: flex; align-items: center; gap: 10px; }
.composer-field > span, .composer-field label { color: #7b7b85; font-size: 12px; white-space: nowrap; }
.composer-options input[type=time] { width: 125px; height: 40px; padding: 7px 11px; font-size: 14px; border-color: #dedee3; border-radius: 10px; }
.composer-options input[type=time]:disabled { background: #fafafa; opacity: .65; }
.composer-options input[type=checkbox] { width: 17px; height: 17px; margin: 0; }
.composer-bottom { margin-top: 7px; gap: 12px; }
.composer-bottom > .secondary { font-size: 12px; color: #71717b; min-width: 105px; padding: 7px 13px; border-radius: 9px; }
.composer-bottom .text-button { font-size: 12px; }
.composer-bottom .primary { min-width: 84px; height: 40px; padding: 8px 20px; font-size: 14px; border-radius: 10px; }
.composer-steps { font-size: 14px; line-height: 1.8; }
@media (max-width: 960px) {
  .composer-options { gap: 12px 17px; }
}
@media (max-width: 700px) {
  .composer { padding: 18px 16px 15px; }
  .composer-title { font-size: 16px; }
  .composer-options { gap: 12px; }
  .reminder-field { flex-basis: 100%; }
  .composer-field { gap: 8px; }
  .composer-options :deep(.select-field) { min-width: 100px; }
  .composer-bottom > div { gap: 5px; }
  .composer-bottom > .secondary { min-width: auto; padding: 7px 10px; }
  .composer-bottom .primary { min-width: 65px; padding: 8px 14px; }
}
</style>
