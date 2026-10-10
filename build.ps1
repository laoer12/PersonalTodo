param(
    [switch]$Package,
    [switch]$Preview,
    [switch]$SkipTests,
    [string]$PackageDirectory = (Join-Path $PSScriptRoot 'release'),
    [string]$JdkDirectory = '',
    [string]$Version = '2.1.0'
)
$ErrorActionPreference = 'Stop'
$todoRoot = $PSScriptRoot
$todoTemp = Join-Path $todoRoot 'runtime\tmp'
$todoLogs = Join-Path $todoRoot 'runtime\logs'
New-Item -ItemType Directory -Path $todoTemp,$todoLogs -Force | Out-Null
$todoOriginalTemp = $env:TEMP
$todoOriginalTmp = $env:TMP
$todoOriginalCache = $env:npm_config_cache
$env:TEMP = $todoTemp
$env:TMP = $todoTemp
try {
$todoBuild = Join-Path $todoRoot 'build'
$todoClasses = Join-Path $todoBuild 'classes'
$todoInput = Join-Path $todoBuild 'input'
New-Item -ItemType Directory -Path $todoClasses,$todoInput -Force | Out-Null
$env:npm_config_cache = Join-Path $todoRoot 'runtime\npm-cache'
Push-Location (Join-Path $todoRoot 'frontend')
try {
    if (-not (Test-Path -LiteralPath 'node_modules\vite')) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Vue build failed.' }
} finally { Pop-Location }
$todoWeb = Join-Path $todoClasses 'web'
if (Test-Path -LiteralPath $todoWeb) {
    if (-not ([System.IO.Path]::GetFullPath($todoWeb).StartsWith($todoBuild + '\', [System.StringComparison]::OrdinalIgnoreCase))) { throw 'Unexpected web output path.' }
    Remove-Item -LiteralPath $todoWeb -Recurse -Force
}
New-Item -ItemType Directory -Path $todoWeb -Force | Out-Null
Copy-Item -Path (Join-Path $todoRoot 'frontend\dist\*') -Destination $todoWeb -Recurse -Force
$todoJdk = $JdkDirectory
if (-not $todoJdk -and $env:JAVA_HOME) { $todoJdk = $env:JAVA_HOME }
if (-not $todoJdk) { $todoJavaCommand = (Get-Command javac -ErrorAction Stop).Source; $todoJdk = Split-Path (Split-Path $todoJavaCommand -Parent) -Parent }
if (-not (Test-Path -LiteralPath (Join-Path $todoJdk 'bin\javac.exe'))) { throw 'Install JDK 21+ and set JAVA_HOME, PATH, or -JdkDirectory.' }
$todoVmOptions = @('-Xms16m', '-Xmx192m', '-XX:+UseSerialGC', '-XX:-UsePerfData', '-Dfile.encoding=UTF-8', ('-Dpersonal.todo.home=' + $todoRoot), ('-Djava.io.tmpdir=' + $todoTemp), ('-XX:ErrorFile=' + (Join-Path $todoLogs 'hs_err_pid%p.log')), ('-XX:HeapDumpPath=' + $todoLogs))
$todoSources = @(Get-ChildItem -LiteralPath (Join-Path $todoRoot 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& (Join-Path $todoJdk 'bin\javac.exe') -encoding UTF-8 --release 21 -d $todoClasses $todoSources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
$todoJar = Join-Path $todoInput 'personal-todo.jar'
& (Join-Path $todoJdk 'bin\jar.exe') --create --file $todoJar --main-class personal.todo.App -C $todoClasses .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }
if (-not $SkipTests) {
    & (Join-Path $todoJdk 'bin\java.exe') $todoVmOptions -jar $todoJar --self-test
    if ($LASTEXITCODE -ne 0) { throw 'Core tests failed.' }
    & (Join-Path $todoJdk 'bin\java.exe') $todoVmOptions -jar $todoJar --web-integration-test
    if ($LASTEXITCODE -ne 0) { throw 'Native tray/reminder checks failed.' }
}
if ($Preview -or $Package) {
    & (Join-Path $todoJdk 'bin\java.exe') $todoVmOptions -jar $todoJar --preview (Join-Path $todoBuild 'preview.png')
    if ($LASTEXITCODE -ne 0) { throw 'Preview failed.' }
}
if ($Package) {
    $todoRelease = [System.IO.Path]::GetFullPath($PackageDirectory)
    New-Item -ItemType Directory -Path $todoRelease -Force | Out-Null
    $todoImage = Join-Path $todoRelease 'PersonalTodo'
    if (Test-Path -LiteralPath $todoImage) {
        throw 'release\PersonalTodo already exists. Move it aside before rebuilding the portable app.'
    }
    $todoPackageArguments = @('--type', 'app-image', '--name', 'PersonalTodo', '--app-version', $Version, '--vendor', 'laoer12', '--description', 'Local daily planner with Vue and Java', '--input', $todoInput, '--main-jar', 'personal-todo.jar', '--main-class', 'personal.todo.App', '--dest', $todoRelease, '--icon', (Join-Path $todoBuild 'icon.ico'), '--add-modules', 'java.desktop,java.logging,jdk.httpserver')
    # Runtime paths expand on the recipient's computer, including folders with spaces.
    $todoPortableOptions = @('-Xms16m', '-Xmx192m', '-XX:+UseSerialGC', '-XX:-UsePerfData', '-Dfile.encoding=UTF-8', '"-Djava.io.tmpdir=$APPDIR/../runtime/tmp"', '"-XX:ErrorFile=$APPDIR/../runtime/logs/hs_err_pid%p.log"', '"-XX:HeapDumpPath=$APPDIR/../runtime/logs"')
    foreach ($todoOption in $todoPortableOptions) { $todoPackageArguments += @('--java-options', $todoOption) }
    & (Join-Path $todoJdk 'bin\jpackage.exe') $todoPackageArguments
    if ($LASTEXITCODE -ne 0) { throw 'Portable packaging failed.' }
    Copy-Item -LiteralPath (Join-Path $todoRoot 'README.md') -Destination (Join-Path $todoImage 'README.md')
    foreach ($todoLicense in @('LICENSE','THIRD_PARTY_NOTICES.md')) { Copy-Item -LiteralPath (Join-Path $todoRoot $todoLicense) -Destination $todoImage }
    Copy-Item -LiteralPath (Join-Path $todoRoot 'docs\licenses') -Destination (Join-Path $todoImage 'licenses') -Recurse
    New-Item -ItemType Directory -Path (Join-Path $todoImage 'runtime\tmp'),(Join-Path $todoImage 'runtime\logs') -Force | Out-Null
    $todoJdkRelease = Get-Content -LiteralPath (Join-Path $todoJdk 'release') -Raw
    $todoRuntimeVersion = [regex]::Match($todoJdkRelease, '(?m)^JAVA_RUNTIME_VERSION="([^"]+)"').Groups[1].Value
    $todoRuntimeVendor = [regex]::Match($todoJdkRelease, '(?m)^IMPLEMENTOR="([^"]+)"').Groups[1].Value
    $todoRuntimeNotice = @('# Bundled Java runtime', '', ('Vendor: ' + $todoRuntimeVendor), ('Version: ' + $todoRuntimeVersion), '', 'The original runtime legal notices are retained in runtime/legal. They have their own terms and are not replaced by the application MIT license.')
    if ($todoRuntimeVendor -eq 'Eclipse Adoptium') {
        $todoTemurinVersion = [regex]::Match($todoJdkRelease, '(?m)^IMPLEMENTOR_VERSION="Temurin-([^"]+)"').Groups[1].Value
        $todoTemurinTag = [Uri]::EscapeDataString('jdk-' + $todoTemurinVersion)
        $todoRuntimeNotice += @('', 'License: GPLv2 with Classpath Exception and the additional component notices in runtime/legal.', ('Exact binary and corresponding source release: https://github.com/adoptium/temurin21-binaries/releases/tag/' + $todoTemurinTag), 'OpenJDK source repository: https://github.com/adoptium/jdk21u')
    }
    $todoRuntimeNotice | Set-Content -LiteralPath (Join-Path $todoImage 'JAVA_RUNTIME_NOTICE.md') -Encoding UTF8
    if (Test-Path -LiteralPath (Join-Path $todoRoot 'TESTING.md')) {
        Copy-Item -LiteralPath (Join-Path $todoRoot 'TESTING.md') -Destination (Join-Path $todoImage 'TESTING.md')
    }
    Compress-Archive -LiteralPath $todoImage -DestinationPath (Join-Path $todoRelease ('PersonalTodo-' + $Version + '-Windows-x64.zip')) -Force
    Write-Output ('Portable executable: ' + (Join-Path $todoImage 'PersonalTodo.exe'))
}
Write-Output ('JAR: ' + $todoJar)
} finally {
    $env:TEMP = $todoOriginalTemp
    $env:TMP = $todoOriginalTmp
    $env:npm_config_cache = $todoOriginalCache
}
