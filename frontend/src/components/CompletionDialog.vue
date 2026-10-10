<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { IconX } from '@tabler/icons-vue'

const props = defineProps({ task: Object, date: String, initial: String, busy: Boolean, error: String, mode: String })
const emit = defineEmits(['save', 'cancel'])
const text = ref(props.initial || ''), field = ref(null), dialog = ref(null), composing = ref(false)
let previousFocus
function cancel() { if (!props.busy && !composing.value) emit('cancel') }
function submit() { if (!props.busy && !composing.value) emit('save', text.value) }
function keyboard(event) {
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); cancel() }
  if (event.key === 'Tab') {
    const controls = [...dialog.value.querySelectorAll('button:not(:disabled),textarea:not(:disabled),[tabindex="0"]')]
    if (!controls.length) { event.preventDefault(); return }
    const first = controls[0], last = controls.at(-1)
    if (event.shiftKey && (document.activeElement === first || !dialog.value.contains(document.activeElement))) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && (document.activeElement === last || !dialog.value.contains(document.activeElement))) { event.preventDefault(); first.focus() }
  }
  if (event.ctrlKey && event.key === 'Enter') { event.preventDefault(); submit() }
}
onMounted(() => { previousFocus = document.activeElement; field.value.focus(); window.addEventListener('keydown', keyboard, true) })
onUnmounted(() => { window.removeEventListener('keydown', keyboard, true); if (previousFocus?.isConnected) previousFocus.focus() })
</script>

<template>
  <div class="modal-backdrop" @click.self="cancel">
    <section ref="dialog" class="modal completion-dialog" role="dialog" aria-modal="true" aria-labelledby="completion-title" aria-describedby="completion-task">
      <div class="modal-heading"><h2 id="completion-title">{{ mode === 'report' ? '修改完成情况' : '记录完成情况' }}</h2><button class="icon-button" aria-label="关闭完成情况" :disabled="busy" @click="cancel"><IconX :size="17" /></button></div>
      <p id="completion-task" class="completion-task-name">{{ task.title }}</p><p class="completion-date">{{ date }}</p>
      <form @submit.prevent="submit">
        <label class="editor-field">完成情况 <span class="completion-optional">选填</span><textarea ref="field" v-model="text" aria-label="完成情况" rows="6" maxlength="10000" :disabled="busy" placeholder="做了哪些事？结果如何？还有什么需要跟进？" @compositionstart="composing = true" @compositionend="composing = false"></textarea></label>
        <div class="completion-hint"><span>{{ mode === 'report' ? '修改该日的完成情况，保留原有完成状态。' : '保存后将勾选当天事项，内容随当天记录保留。' }}</span><span>{{ text.length.toLocaleString() }} / 10,000</span></div>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <div class="modal-footer"><button type="button" class="secondary" :disabled="busy || composing" @click="cancel">取消</button><button type="submit" class="primary" :disabled="busy || composing">{{ busy ? '正在保存…' : mode === 'report' ? '保存修改' : '保存并完成' }}</button></div>
      </form>
    </section>
  </div>
</template>
