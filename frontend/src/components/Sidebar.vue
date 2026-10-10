<script setup>
defineProps({ view: String, selected: String, busy: Boolean })
defineEmits(['navigate', 'new', 'date', 'folder', 'exit'])
const groups = [
  { name: '日程', items: [['week', '本周计划'], ['today', '今天'], ['tomorrow', '明天'], ['plans', '全部计划']] },
  { name: '笔记', items: [['memos', '备忘录']] },
  { name: '回顾', items: [['history', '历史与复盘'], ['templates', '每日模板']] },
  { name: '偏好', items: [['settings', '设置与备份']] },
]
</script>

<template>
  <aside class="sidebar">
    <div class="brand"><img src="/icon.svg" alt="" /><div><strong>每日计划</strong><span>专注今天，保持从容</span></div></div>
    <button class="primary new-task" :disabled="busy" @click="$emit('new')">＋&nbsp; 新建待办</button>
    <nav aria-label="主导航">
      <section v-for="group in groups" :key="group.name" class="nav-group">
        <div class="nav-caption">{{ group.name }}</div>
        <template v-for="[key, title] in group.items" :key="key">
          <button :class="['nav-item', { active: view === key }]" :aria-current="view === key ? 'page' : undefined" :disabled="busy" @click="$emit('navigate', key)">{{ title }}</button>
          <label v-if="key === 'tomorrow'" class="date-nav">选择日期<input aria-label="选择日期" type="date" :value="selected" :disabled="busy" @change="$emit('date', $event.target.value)" /></label>
        </template>
      </section>
    </nav>
    <div class="sidebar-footer">
      <button class="text-button" @click="$emit('folder', 'data')">打开本地数据目录</button>
      <span>•&nbsp; 本地模式</span>
      <button class="text-button" :disabled="busy" @click="$emit('exit')">完全退出</button>
    </div>
  </aside>
</template>
