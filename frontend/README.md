# 从这个项目学习 Vue

界面使用 Vue 3 单文件组件、Composition API 和普通 CSS；暂不引入路由、Pinia 或大型组件库，方便直接阅读。

## 建议阅读顺序

| 文件 | 负责什么 | 可以学习什么 |
| --- | --- | --- |
| `src/main.js` | 创建应用，导入样式 | `createApp` 与入口 |
| `src/components/TaskComposer.vue` | 快速添加 | `reactive`、`v-model`、表单事件、`defineEmits` |
| `src/components/TaskRow.vue` | 任务与子任务 | `defineProps`、`computed`、`v-for`、条件渲染 |
| `src/components/CompletionDialog.vue` | 今日事项完成情况输入 | `ref`、表单提交、输入法组合事件、焦点管理、父组件提交后再更新状态 |
| `src/components/Sidebar.vue` | 导航 | 父子组件传值与事件 |
| `src/components/PeriodPlan.vue` | 本周及自选周期 | 日期表单、分组列表、计算属性、带日期的组件事件 |
| `src/components/MemoPage.vue` | 备忘录列表、编辑、预览和回收站 | `computed` 搜索排序、自动保存、输入法事件、组件 `defineExpose`、异步操作排队 |
| `src/markdown.js` | Markdown 解析和预览清理 | 第三方库调用、预览与原始正文分离 |
| `src/components/TaskEditor.vue` | 编辑弹窗 | 校验、生命周期、键盘事件 |
| `src/components/DailyReview.vue` | 复盘输入 | 输入事件、`watch` |
| `src/components/SettingsPanel.vue` | 偏好与备份 | 开关、文件选择、组件事件 |
| `src/App.vue` | 页面与数据协调 | `ref`、`computed`、异步请求、写操作排队 |
| `src/api.js` | Java 接口 | `fetch`、异常、同源请求 |
| `src/style.css` | 黑灰样式与响应式布局 | Flex、Grid、媒体查询 |

例如：输入时 `v-model` 更新 TaskComposer 的 form.title；提交触发 `emit('add', task)`；App.vue 调用 Java；Java 校验并保存本机文件；页面读取新状态显示任务。重复规则、备份和提醒由 Java 统一处理。

## 开发

在项目根目录运行 `dev.ps1`。脚本将 TEMP/TMP 和 npm 缓存设为项目目录，必要时启动 Java 后台，再启动只监听 127.0.0.1 的 Vite。用终端显示的地址打开页面。Vite 将 `/api` 转发到 Java；后台重启后需重启 Vite，读取新的端口和令牌。

开发页面读写与桌面版相同的个人数据。做破坏性实验前先导出备份；自动化测试自行创建隔离的测试目录。修改 Vue/CSS 会热更新；修改 Java 后退出旧后台、运行根目录 build.ps1 再启动。Ctrl+C 停止开发服务器，后台从托盘“完全退出”停止。

```powershell
# 在 frontend 目录
npm.cmd run build
npm.cmd run test:e2e
npm.cmd run test:memos
npm.cmd run test:completion
```

build 生成 dist；根目录构建脚本将 dist 嵌入 JAR，因此最终 exe 不依赖 Vite 或 Node。

可以先练习修改样式、添加显示标签，再学习如何同时修改 Vue 表单、Java 校验与持久化兼容规则。

官方资料：[Vue 入门](https://cn.vuejs.org/guide/introduction.html)、[单文件组件](https://cn.vuejs.org/guide/scaling-up/sfc.html)、[Vite 指南](https://cn.vite.dev/guide/)。

备忘录预览使用 [Marked](https://marked.js.org/using_advanced) 解析并通过 [DOMPurify](https://github.com/cure53/DOMPurify) 清理输出；图标使用 [Tabler Icons Vue](https://tabler.io/icons/packages)。这些依赖已打包进本地页面，不从 CDN 下载。正文由 Java 保存到工作目录 `data/data.json` 的 memos 字段，浏览器只维护当前编辑草稿。
