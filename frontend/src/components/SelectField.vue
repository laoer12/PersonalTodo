<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue'
import { IconCheck, IconChevronDown } from '@tabler/icons-vue'

const props = defineProps({ modelValue: [String, Number], label: String, options: Array, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const id = useId(), root = ref(null), trigger = ref(null), list = ref(null)
const opened = ref(false), active = ref(0)
const selected = computed(() => props.options.findIndex(option => option.value === props.modelValue))
const text = computed(() => props.options[selected.value]?.label || '')

async function open() {
  if (props.disabled) return
  active.value = Math.max(0, selected.value); opened.value = true
  await nextTick(); list.value?.focus()
}
function close(restoreFocus = false) {
  opened.value = false
  if (restoreFocus) trigger.value?.focus()
}
function choose(index) {
  if (props.disabled) return
  emit('update:modelValue', props.options[index].value); close(true)
}
async function keydown(event) {
  const key = event.key
  if (key === 'Escape' || key === 'Tab') { if (key === 'Escape') event.preventDefault(); close(true); return }
  if (key === 'Enter' || key === ' ') { event.preventDefault(); choose(active.value); return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(key)) return
  event.preventDefault()
  active.value = key === 'Home' ? 0 : key === 'End' ? props.options.length - 1 : (active.value + (key === 'ArrowDown' ? 1 : -1) + props.options.length) % props.options.length
  await nextTick(); list.value?.children[active.value]?.scrollIntoView({ block: 'nearest' })
}
function outside(event) { if (!root.value?.contains(event.target)) close() }
function focusout(event) { if (!root.value?.contains(event.relatedTarget)) close() }
watch(() => props.disabled, value => { if (value) close() })
onMounted(() => document.addEventListener('pointerdown', outside))
onUnmounted(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div ref="root" class="select-field" @focusout="focusout">
    <button ref="trigger" type="button" class="select-trigger" :aria-label="label" aria-haspopup="listbox"
      :aria-expanded="opened" :aria-controls="opened ? id : undefined" :disabled="disabled"
      @click="opened ? close(true) : open()" @keydown.down.prevent="open" @keydown.up.prevent="open">
      <span>{{ text }}</span><IconChevronDown :size="16" :stroke="1.6" aria-hidden="true" />
    </button>
    <div v-if="opened" :id="id" ref="list" class="select-menu" role="listbox" :aria-label="label"
      tabindex="-1" :aria-activedescendant="`${id}-${active}`" @keydown="keydown">
      <div v-for="(option, index) in options" :id="`${id}-${index}`" :key="option.value"
        role="option" :aria-selected="index === selected" :class="['select-option', { active: index === active }]"
        @pointermove="active = index" @pointerdown.prevent @click="choose(index)">
        <span>{{ option.label }}</span><IconCheck v-if="index === selected" :size="16" :stroke="1.8" aria-hidden="true" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.select-field { position: relative; min-width: 116px; }
.select-trigger { display: flex; justify-content: space-between; align-items: center; gap: 22px; width: 100%; height: 40px; padding: 0 12px; border: 1px solid #dedee3; border-radius: 10px; background: #fff; font-size: 14px; color: #303034; transition: border-color .15s, background .15s; }
.select-trigger:hover:not(:disabled), .select-trigger[aria-expanded=true] { border-color: #a7a7af; background: #fafafa; }
.select-trigger svg { color: #777780; flex-shrink: 0; }
.select-menu { position: absolute; top: calc(100% + 7px); left: 0; z-index: 10; width: max(100%, 142px); padding: 5px; border: 1px solid #e4e4e8; border-radius: 12px; background: #fff; box-shadow: 0 8px 24px #22222614, 0 2px 6px #22222608; max-height: 260px; overflow-y: auto; outline: none; }
.select-option { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 8px 10px; min-height: 38px; border-radius: 7px; font-size: 14px; line-height: 1.5; color: #39393f; cursor: pointer; }
.select-option.active { background: #f0f0f2; color: #222225; }
.select-option[aria-selected=true] { font-weight: 550; }
</style>
