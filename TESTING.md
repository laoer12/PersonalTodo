# 验证说明

本项目在 Windows 桌面环境验证。测试数据写入独立临时目录，不使用个人数据。

| 检查 | 入口 | 内容 |
| --- | --- | --- |
| Java 核心 | build.ps1 / JAR --self-test | 日期规则、持久化、数据锁、备份、冲突、备忘录、完成情况与可移动路径 |
| 原生桌面 | build.ps1 / JAR --web-integration-test | 系统托盘、提醒调度、浮窗、去重和退出 |
| 待办 / HTTP | npm.cmd run test:e2e | 认证、任务、周期、子任务、模板、复盘、备份、重启和关闭 |
| 历史与菜单 | npm.cmd run test:history-ui | 任意日期、切换保存、历史模式、菜单鼠标 / 键盘与窄窗口 |
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
