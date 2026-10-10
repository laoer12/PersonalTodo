<script setup>
const props = defineProps({ settings: Object, paths: Object, startup: Boolean, startupAvailable: Boolean, busy: Boolean })
const emit = defineEmits(['settings', 'startup', 'reminder', 'export', 'import', 'folder'])
function setting(key, value) { emit('settings', { ...props.settings, [key]: value }) }
async function importFile(event) {
  const file = event.target.files[0]
  if (file) emit('import', file)
  event.target.value = ''
}
</script>
<template>
  <div class="settings-content">
    <section class="settings-section"><h2>运行与提醒</h2>
      <label class="setting-row"><span>关闭窗口后留在后台提醒</span><input class="switch" type="checkbox" :checked="settings.closeToTray" :disabled="busy" @change="setting('closeToTray', $event.target.checked)" /></label>
      <label class="setting-row"><span>提醒时播放提示音</span><input class="switch" type="checkbox" :checked="settings.sound" :disabled="busy" @change="setting('sound', $event.target.checked)" /></label>
      <label class="setting-row"><span>开机启动<span v-if="!startupAvailable" class="setting-note">请从打包程序中设置</span></span><input class="switch" type="checkbox" :checked="startup" :disabled="busy || !startupAvailable" @change="$emit('startup', $event.target.checked)" /></label>
      <p class="muted">后台每 20 秒检查一次，仅补发最近 30 分钟内的提醒。工作日按周一至周五计算。</p>
      <button class="secondary" :disabled="busy" @click="$emit('reminder')">测试提醒</button>
    </section>
    <section class="settings-section"><h2>数据与备份</h2><code>{{ paths.data }}</code>
      <div class="settings-buttons"><button class="secondary" :disabled="busy" @click="$emit('export')">导出备份</button><label class="secondary file-button">导入 / 恢复<input type="file" aria-label="导入备份" accept=".json,application/json" :disabled="busy" @change="importFile" /></label><button class="secondary" @click="$emit('folder', 'backups')">打开备份目录</button></div>
      <p class="muted">备份包含待办、复盘、备忘录和回收站。导入前会保存当前数据快照；导入不含备忘录的旧备份会保留现有笔记。导出文件位于数据目录的 exports 文件夹。</p>
    </section>
    <section class="settings-section"><h2>应用目录</h2><dl><dt>源码与程序</dt><dd>{{ paths.root }}</dd><dt>日志</dt><dd>{{ paths.logs }}</dd><dt>临时文件</dt><dd>{{ paths.temp }}</dd></dl></section>
  </div>
</template>
