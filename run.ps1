param([switch]$Packaged)
$ErrorActionPreference = 'Stop'
$todoTemp = Join-Path $PSScriptRoot 'runtime\tmp'
$todoLogs = Join-Path $PSScriptRoot 'runtime\logs'
New-Item -ItemType Directory -Path $todoTemp,$todoLogs -Force | Out-Null
$todoOriginalTemp = $env:TEMP
$todoOriginalTmp = $env:TMP
$env:TEMP = $todoTemp
$env:TMP = $todoTemp
try {
$todoExecutable = Join-Path $PSScriptRoot 'release\PersonalTodo\PersonalTodo.exe'
if ($Packaged) {
    if (-not (Test-Path -LiteralPath $todoExecutable)) { throw 'Build the portable app first with .\build.ps1 -Package.' }
    Start-Process -FilePath $todoExecutable -WindowStyle Hidden
} else {
    $todoJar = Join-Path $PSScriptRoot 'build\input\personal-todo.jar'
    if (-not (Test-Path -LiteralPath $todoJar)) { & (Join-Path $PSScriptRoot 'build.ps1') }
    $todoJavaw = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javaw.exe' } else { (Get-Command javaw -ErrorAction Stop).Source }
    $todoVmOptions = @('-Xms16m', '-Xmx192m', '-XX:+UseSerialGC', '-XX:-UsePerfData', '-Dfile.encoding=UTF-8', ('"-Dpersonal.todo.home=' + $PSScriptRoot + '"'), ('"-Djava.io.tmpdir=' + $todoTemp + '"'), ('"-XX:ErrorFile=' + (Join-Path $todoLogs 'hs_err_pid%p.log') + '"'), ('"-XX:HeapDumpPath=' + $todoLogs + '"'))
    Start-Process -FilePath $todoJavaw -ArgumentList ($todoVmOptions + @('-jar', ('"' + $todoJar + '"'))) -WindowStyle Hidden
}
} finally {
    $env:TEMP = $todoOriginalTemp
    $env:TMP = $todoOriginalTmp
}
