$ErrorActionPreference = 'Stop'
$todoTemp = Join-Path $PSScriptRoot 'runtime\tmp'
New-Item -ItemType Directory -Path $todoTemp -Force | Out-Null
$todoOldTemp = $env:TEMP
$todoOldTmp = $env:TMP
$todoOldCache = $env:npm_config_cache
$env:TEMP = $todoTemp
$env:TMP = $todoTemp
$env:npm_config_cache = Join-Path $PSScriptRoot 'runtime\npm-cache'
try {
    $todoInfoFile = Join-Path $PSScriptRoot 'runtime\server.json'
    $todoAlive = $false
    if (Test-Path -LiteralPath $todoInfoFile) {
        try { $todoInfo = Get-Content -LiteralPath $todoInfoFile -Raw | ConvertFrom-Json; $todoAlive = $null -ne (Get-Process -Id $todoInfo.pid -ErrorAction SilentlyContinue) } catch { }
    }
    if (-not $todoAlive) {
        & (Join-Path $PSScriptRoot 'run.ps1')
        $todoDeadline = (Get-Date).AddSeconds(12)
        do { Start-Sleep -Milliseconds 200 } while (-not (Test-Path -LiteralPath $todoInfoFile) -and (Get-Date) -lt $todoDeadline)
        if (-not (Test-Path -LiteralPath $todoInfoFile)) { throw 'Java backend did not start. Check runtime\logs.' }
    }
    Push-Location (Join-Path $PSScriptRoot 'frontend')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules\vite')) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' } }
        & npm.cmd run dev
    } finally { Pop-Location }
} finally { $env:TEMP = $todoOldTemp; $env:TMP = $todoOldTmp; $env:npm_config_cache = $todoOldCache }
