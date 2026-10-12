<script setup>
import { weekDayLabels } from '../api'
const props = defineProps({ modelValue: Array, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
function toggle(day, checked) {
  const days = new Set(props.modelValue)
  if (checked) days.add(day); else days.delete(day)
  emit('update:modelValue', [...days].sort((a, b) => a - b))
}
</script>

<template>
  <fieldset class="weekday-picker" :disabled="disabled">
    <legend>每周重复日期 <span>可多选</span></legend>
    <div class="weekday-options">
      <label v-for="(label, index) in weekDayLabels" :key="label" :class="['weekday-option', { selected: modelValue.includes(index + 1) }]">
        <input type="checkbox" :aria-label="label" :checked="modelValue.includes(index + 1)" @change="toggle(index + 1, $event.target.checked)" /><span>{{ label }}</span>
      </label>
    </div>
    <p v-if="!modelValue.length" class="weekday-error" role="alert">请至少选择一个日期</p>
  </fieldset>
</template>

<style scoped>
.weekday-picker { border: 0; min-width: 0; padding: 0; margin: 0 0 14px; }
.weekday-picker legend { padding: 0; margin-bottom: 8px; font-size: 13px; color: #777780; }
.weekday-picker legend span { font-size: 11px; color: #9999a1; margin-left: 6px; }
.weekday-options { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 7px; }
.weekday-option { display: flex; align-items: center; justify-content: center; position: relative; min-width: 0; margin: 0; padding: 7px 0; border: 1px solid #e1e1e6; border-radius: 8px; color: #66666f; font-size: 13px; cursor: pointer; white-space: nowrap; }
.weekday-option:hover { background: #f5f5f7; }
.weekday-option.selected { color: #fff; background: #232325; border-color: #232325; }
.weekday-option:focus-within { outline: 2px solid #707078; outline-offset: 2px; }
.weekday-option input { position: absolute; width: 1px; height: 1px; opacity: 0; padding: 0; margin: 0; }
.weekday-picker:disabled { opacity: .6; }
.weekday-picker:disabled .weekday-option { cursor: default; }
.weekday-error { font-size: 12px; color: #a34d53; margin-top: 6px; }
@media(max-width:700px) { .weekday-options { gap: 5px; } .weekday-option { font-size: 12px; } }
</style>
