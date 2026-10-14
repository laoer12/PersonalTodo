# 验证说明

本项目在 Windows 桌面环境验证。测试数据写入独立临时目录，不使用个人数据。

| 检查 | 入口 | 内容 |
| --- | --- | --- |
| Java 核心 | build.ps1 / JAR --self-test | 日期规则、持久化、数据锁、备份、冲突、备忘录、完成情况与可移动路径 |
| 原生桌面 | build.ps1 / JAR --web-integration-test | 系统托盘、提醒调度、浮窗、去重和退出 |
| 待办 / HTTP | npm.cmd run test:e2e | 认证、任务、周期、子任务、模板、复盘、备份、重启和关闭 |
| 历史与菜单 | npm.cmd run test:history-ui | 周日期与任意日期、状态统计、历史情况补充 / 修改、失败重试、多窗口冲突、只读 / 编辑、切换保存与窄窗口 |
| 指定星期 | npm.cmd run test:weekly-ui | 星期默认值 / 多选、校验、编辑确认、历史保留、快捷添加、首次日期、窄窗口、重启 |
| 昨日回顾 | npm.cmd run test:startup-review | 首次展示、多窗口与重启去重、正文、失败重试、复盘入口、焦点和窄窗口 |
| 备忘录 | npm.cmd run test:memos | Markdown、失败、冲突、回收站、导出、组合输入和重启 |
| 完成情况 | npm.cmd run test:completion | 弹窗、取消、原子保存、日期隔离、重试和备份 |

Playwright 驱动本机 Edge，不下载额外浏览器。PERSONAL_TODO_BROWSER 可指定浏览器路径，TODO_EXE 可用便携版自带 Java 启动测试。

```powershell
.\build.ps1
Set-Location frontend
npm.cmd run test:e2e
npm.cmd run test:history-ui
npm.cmd run test:weekly-ui
npm.cmd run test:startup-review
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

## 2.3.0 每周指定星期验证（2026-10-12，本机）

共 225 项检查：130 项 Java 核心、9 项原生桌面、41 项待办 / HTTP、30 项历史与编辑器、15 项指定星期界面检查。指定星期套件另用便携版自带运行环境重复验证，不重复计数。

新增 31 项核心检查覆盖单日 / 多日循环、开始 / 结束边界、提醒与去重、旧版数据读取、无效输入不落盘、备份与重启、星期修改保留旧记录、并发编辑冲突、顺延及模板。数据格式仍为 JSON v1，weekDays 为新增可选字段；旧数据按开始日期的星期读取，无需手动迁移。

界面覆盖新建、编辑、快捷添加、至少选一天、周一至周日键盘操作、周规则文字、第一天导航、复盘与 390px 窄窗口。截图：build/weekly-editor-desktop.png、build/weekly-composer-desktop.png、build/weekly-editor-mobile.png。全部使用独立示例数据，未修改个人记录。

## 2.4.0 昨日回顾验证（2026-10-14，本机）

共 256 项检查通过：146 项 Java 核心、9 项原生桌面、41 项待办 / HTTP、30 项历史与编辑器、15 项指定星期、15 项昨日回顾。昨日回顾套件另用便携版自带运行环境重复验证，不重复计数。

新增 16 项核心检查覆盖日期、仅昨日完成事项、已修改日期规则的旧记录、完整 / 缺失正文、计划数据及 revision 不变、回执重启持久化、次日展示、空日跳过、同时打开仅一个窗口展示、无效窗口和存储失败。回执保存为数据目录中的 startup-review.json，不混入 data.json 或计划备份。

界面检查覆盖 1487 × 1058 设计对照、1104 × 694 紧凑窗口、390 × 844 窄窗口、长正文键盘滚动、焦点约束、关闭 / 开始今天 / 指定日期复盘、失败重试及响应丢失后的同窗口恢复。所有数据使用隔离目录。

截图：build/yesterday-review-desktop.png、build/yesterday-review-compact.png、build/yesterday-review-long-report.png、build/yesterday-review-mobile.png。
