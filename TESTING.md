# 验证说明

本项目在 Windows 桌面环境验证。测试数据写入独立临时目录，不使用个人数据。

| 检查 | 入口 | 内容 |
| --- | --- | --- |
| Java 核心 | build.ps1 / JAR --self-test | 日期规则、持久化、数据锁、备份、冲突、备忘录、完成情况与可移动路径 |
| 原生桌面 | build.ps1 / JAR --web-integration-test | 系统托盘、提醒调度、浮窗、去重和退出 |
| 待办 / HTTP | npm.cmd run test:e2e | 认证、任务、周期、子任务、模板、复盘、备份、重启和关闭 |
| 历史与菜单 | npm.cmd run test:history-ui | 周日期与任意日期、状态统计、历史情况补充 / 修改、失败重试、多窗口冲突、只读 / 编辑、切换保存与窄窗口 |
| 备忘录 | npm.cmd run test:memos | Markdown、失败、冲突、回收站、导出、组合输入和重启 |
| 完成情况 | npm.cmd run test:completion | 弹窗、取消、原子保存、日期隔离、重试和备份 |

Playwright 驱动本机 Edge，不下载额外浏览器。PERSONAL_TODO_BROWSER 可指定浏览器路径，TODO_EXE 可用便携版自带 Java 启动测试。

```powershell
.\build.ps1
Set-Location frontend
npm.cmd run test:e2e
npm.cmd run test:history-ui
npm.cmd run test:memos
npm.cmd run test:completion
```

结果与截图在 build，隔离数据在 runtime/tmp，均不提交 Git。验证便携性时还应在新目录（含空格与中文）构建源码、解压便携包，检查默认路径、创建 / 保存 / 重启，以及 cfg 无作者绝对路径。

未覆盖所有真实输入法、显示缩放、多显示器、休眠 / 断电及通知策略。组合输入使用合成事件验证；开机启动需在对应电脑上实际重启确认。资源占用随环境与数据量变化，JVM 最大堆不代表进程总内存。

原生托盘检查需要可用的 Windows 图形会话，不宣称支持 macOS / Linux。

## 2.1.0 发布验证（2026-10-10）

本机共 204 项检查通过：94 项核心、9 项原生、41 项待办 / HTTP、14 项历史与菜单、21 项备忘录、17 项完成情况、8 项启动 / 移动。已在全新、含空格与中文的 clone 目录安装锁定依赖并构建，没有依赖个人数据或被排除的设计附件。

Windows x64 便携版使用 Temurin 21.0.12.1+1；从实际 ZIP 解压到另一目录，验证默认路径、初始空数据、保存和重启恢复。生成配置使用运行时相对路径，不含作者的绝对目录。正式包的自带运行环境也通过原有 41 项待办 / HTTP 检查。

## 2.2.0 历史复盘更新验证（2026-10-10，本机）

共 212 项检查通过：99 项 Java 核心、9 项原生桌面、41 项待办 / HTTP、25 项历史与菜单、21 项备忘录、17 项完成弹窗。历史与菜单套件另用 2.2.0 便携版自带运行环境再次通过，重复运行不计入总数。

历史页验证覆盖 3/5 完成率、顺延独立统计、任意日期、跨周、历史完成情况补充 / 修改 / 取消、保存失败重试、多窗口冲突、编辑文字不改变完成或提醒状态、空日期复盘、修改开始日期后旧记录仍可见、390px 窄窗口与详情返回。桌面设计对照截图为 build/history-design-desktop.png，使用独立示例数据。

## 2.2.1 紧凑布局与分类验证（2026-10-10，本机）

本次针对前端变更完成 71 项浏览器检查：41 项待办 / HTTP、30 项历史与菜单 / 编辑器。历史与编辑器套件另用 2.2.1 便携版自带运行环境验证，重复运行不计入总数。

新增覆盖 1104 × 694 窗口下的日期栏、事项列表、侧栏与复盘入口，45 行完成情况在正文区域内滚动，新建待办提醒标签 / 复选框 / 时间不重叠，预设与自定义分类保存及再次选择，以及 390px 窄窗口编辑。所有数据与浏览器配置均使用 D 盘隔离目录，未使用真实个人数据。

截图：build/history-compact-desktop.png、build/history-long-report-compact.png、build/task-editor-compact.png、build/task-editor-categories.png、build/task-editor-mobile.png。
