import path from 'node:path'

// Use the contributor's JDK and installed browser; never download browsers in tests.
export const javaExecutable = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java.exe') : 'java'
export const browserOptions = process.env.PERSONAL_TODO_BROWSER
  ? { executablePath: process.env.PERSONAL_TODO_BROWSER }
  : { channel: 'msedge' }
