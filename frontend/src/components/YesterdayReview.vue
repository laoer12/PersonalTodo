<script setup>
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { IconCircleCheckFilled, IconX } from '@tabler/icons-vue'
const props = defineProps({ review: Object, busy: Boolean, error: String })
const emit = defineEmits(['dismiss', 'history'])
const dialog = ref(null), closeButton = ref(null)
const dateText = computed(() => new Date(`${props.review.date}T12:00:00`).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }))
const weekday = computed(() => new Date(`${props.review.date}T12:00:00`).toLocaleDateString('zh-CN', { weekday: 'long' }))
let previousFocus, previousOverflow
function dismiss() { if (!props.busy) emit('dismiss') }
function keyboard(event) {
  if (event.isComposing) return
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); dismiss() }
  if (event.key === 'Tab') {
    const controls = [...dialog.value.querySelectorAll('button:not(:disabled), [tabindex="0"]')]
    const first = controls[0], last = controls.at(-1)
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
}
onMounted(async () => {
  previousFocus = document.activeElement; previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'; window.addEventListener('keydown', keyboard, true)
  await nextTick(); closeButton.value?.focus({ preventScroll: true })
})
onUnmounted(() => {
  document.body.style.overflow = previousOverflow; window.removeEventListener('keydown', keyboard, true)
  nextTick(() => { if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true }) })
})
</script>

<template>
  <div class="modal-backdrop yesterday-backdrop">
    <section ref="dialog" class="modal yesterday-review" role="dialog" aria-modal="true" aria-labelledby="yesterday-title" aria-describedby="yesterday-date">
      <header class="yesterday-heading">
        <div><h2 id="yesterday-title">昨日回顾</h2><p id="yesterday-date">{{ dateText }} · {{ weekday }}</p><p class="yesterday-count">昨天完成了 <strong>{{ review.items.length }}</strong> 项事项</p></div>
        <button ref="closeButton" class="icon-button yesterday-close" aria-label="关闭昨日回顾" :disabled="busy" @click="dismiss"><IconX :size="20" /></button>
      </header>
      <div class="yesterday-items" role="region" aria-label="昨日已完成事项" tabindex="0">
        <article v-for="item in review.items" :key="item.id" class="yesterday-item" :data-task-id="item.id">
          <IconCircleCheckFilled class="yesterday-check" :size="38" />
          <div class="yesterday-item-content"><div class="yesterday-item-heading"><h3>{{ item.title }}</h3><span>已完成</span></div><p class="yesterday-category">{{ item.category }}</p><p class="yesterday-report-label">完成情况</p><p :class="['yesterday-report', { 'yesterday-missing': !item.report.trim() }]">{{ item.report.trim() ? item.report : '未填写完成情况' }}</p></div>
        </article>
      </div>
      <p v-if="error" class="error-banner yesterday-error" role="alert">{{ error }}</p>
      <footer class="yesterday-footer"><button class="secondary" :disabled="busy" @click="emit('history')">{{ busy ? '正在打开…' : '查看昨日复盘' }}</button><button class="primary" :disabled="busy" @click="dismiss">开始今天</button></footer>
    </section>
  </div>
</template>

<style scoped>
.yesterday-backdrop { background: #0005; backdrop-filter: blur(3px); }
.yesterday-review { width: 830px; height: 820px; max-width: 100%; max-height: calc(100dvh - 50px); padding: 26px 30px 22px; display: flex; flex-direction: column; overflow: hidden; border-radius: 18px; }
.yesterday-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 18px; flex-shrink: 0; }
.yesterday-heading h2 { font-size: 36px; line-height: 1.35; font-weight: 650; letter-spacing: -.7px; }
.yesterday-heading p { margin-top: 9px; color: #8c8c95; font-size: 16px; }
.yesterday-heading .yesterday-count { margin-top: 12px; }
.yesterday-count strong { font-size: 23px; font-weight: 650; color: #242426; margin: 0 7px; }
.yesterday-close { width: 43px; height: 43px; flex-shrink: 0; }
.yesterday-close:focus-visible { outline-offset: 2px; }
.yesterday-items { min-height: 0; flex: 1; overflow-y: auto; scrollbar-gutter: stable; border: 1px solid #e5e5ea; border-radius: 11px; padding: 0 22px; }
.yesterday-items:focus-visible { outline: 2px solid #707078; outline-offset: 2px; }
.yesterday-item { display: flex; gap: 24px; padding: 24px 0 27px; }
.yesterday-item + .yesterday-item { border-top: 1px solid #ededf0; }
.yesterday-check { color: #232325; flex-shrink: 0; margin-top: 2px; }
.yesterday-item-content { min-width: 0; flex: 1; }
.yesterday-item-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.yesterday-item-heading h3 { margin: 0; font-size: 18px; line-height: 1.6; font-weight: 600; overflow-wrap: anywhere; }
.yesterday-item-heading > span { padding: 3px 12px; background: #f4f4f6; border-radius: 9px; color: #7b7b84; font-size: 12px; white-space: nowrap; flex-shrink: 0; }
.yesterday-category { color: #909099; font-size: 14px; margin-top: 2px; }
.yesterday-report-label { font-size: 13px; color: #83838c; margin-top: 15px; margin-bottom: 5px; }
.yesterday-report { font-size: 15px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.yesterday-missing { color: #aaaab2; font-style: italic; }
.yesterday-footer { margin-top: 18px; padding-top: 13px; border-top: 1px solid #e5e5ea; display: flex; justify-content: flex-end; gap: 12px; flex-shrink: 0; }
.yesterday-footer button { font-size: 15px; min-height: 46px; padding: 10px 23px; }
.yesterday-error { margin: 10px 0 0; font-size: 13px; flex-shrink: 0; }
@media(max-height:760px) and (min-width:701px) {
 .yesterday-review { padding: 20px 24px 18px; }
 .yesterday-heading { margin-bottom: 12px; }
 .yesterday-heading h2 { font-size: 27px; }
 .yesterday-heading p { font-size: 14px; margin-top: 6px; }
 .yesterday-heading .yesterday-count { margin-top: 7px; }
 .yesterday-item { padding: 17px 0 20px; }
 .yesterday-check { width: 30px; height: 30px; }
 .yesterday-item-heading h3 { font-size: 17px; }
 .yesterday-footer { margin-top: 12px; padding-top: 11px; }
}
@media(max-width:700px) {
 .yesterday-review { max-height: calc(100dvh - 24px); padding: 20px 16px 16px; }
 .yesterday-heading { gap: 12px; margin-bottom: 12px; }
 .yesterday-heading h2 { font-size: 26px; }
 .yesterday-heading p { font-size: 13px; }
 .yesterday-close { width: 35px; height: 35px; }
 .yesterday-items { padding: 0 12px; }
 .yesterday-item { gap: 10px; padding: 18px 0; }
 .yesterday-check { width: 24px; height: 24px; }
 .yesterday-item-heading { gap: 8px; }
 .yesterday-item-heading h3 { font-size: 15px; }
 .yesterday-item-heading > span { font-size: 11px; padding: 3px 7px; }
 .yesterday-category, .yesterday-report-label { font-size: 12px; }
 .yesterday-report { font-size: 14px; }
 .yesterday-footer { gap: 8px; margin-top: 12px; padding-top: 12px; }
 .yesterday-footer button { min-height: 42px; font-size: 13px; padding: 9px 12px; }
}
</style>
