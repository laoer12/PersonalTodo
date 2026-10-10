<script setup>
import { ref, watch } from 'vue'
const props = defineProps({ value: String, date: String, history: Boolean, saving: Boolean })
defineEmits(['change'])
const expanded = ref(props.history)
watch(() => props.date, () => { expanded.value = props.history })
</script>
<template>
  <section class="daily-review">
    <div><span>一句话复盘</span><button class="text-button" @click="expanded = !expanded">{{ expanded ? '收起' : '展开复盘' }}</button></div>
    <template v-if="expanded"><textarea :value="value" aria-label="一句话复盘" maxlength="10000" rows="3" placeholder="今天有什么进展？明天想调整什么？" @input="$emit('change', $event.target.value)" /><small v-if="saving" role="status">正在保存…</small></template>
  </section>
</template>
