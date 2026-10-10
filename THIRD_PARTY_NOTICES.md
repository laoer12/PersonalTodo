# Third-party notices

PersonalTodo's original source code is licensed under MIT (see LICENSE). Dependencies and the bundled Java runtime retain their own licenses.

| Component | License | Upstream |
| --- | --- | --- |
| Vue and bundled @vue runtime packages | MIT | https://github.com/vuejs/core |
| Tabler Icons / @tabler/icons-vue | MIT | https://github.com/tabler/tabler-icons |
| Marked | MIT | https://github.com/markedjs/marked |
| DOMPurify | Apache-2.0 OR MPL-2.0; this distribution uses Apache-2.0 | https://github.com/cure53/DOMPurify |

Unmodified license texts and copyright notices are in docs/licenses, and copied to licenses in the portable package. Build/test tools (Vite, its plugin and Playwright) are installed by npm and are not shipped as a separate runtime in the portable package; their license files remain in the installed packages.

Official Windows portable releases are built with Eclipse Temurin 21. Its OpenJDK runtime is covered by GPLv2 with the Classpath Exception and component notices under runtime/legal. The exact release, source URL and checksum are supplied in JAVA_RUNTIME_NOTICE.md inside the package. Source releases: https://github.com/adoptium/temurin21-binaries/releases and https://github.com/adoptium/jdk21u . Preserve the runtime's legal files when redistributing.

The visual workflow was inspired by DailyTodo-Windows (https://github.com/wenyu46/DailyTodo-Windows). Form interactions were informed by Primer / Carbon documentation. No upstream application code or reference screenshots are distributed. Screenshots in docs/images show PersonalTodo with isolated test fixtures. The checkmark icon is drawn locally.
