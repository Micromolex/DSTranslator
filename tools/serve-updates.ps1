# Раздача обновлений с этого компьютера.
#
#   .\tools\serve-updates.ps1                  # только локальная сеть, версия +1
#   .\tools\serve-updates.ps1 -Tunnel          # публичная ссылка через Cloudflare (разные сети)
#   .\tools\serve-updates.ps1 -VersionCode 12  # явный versionCode
#
# Локальный режим: планшет должен быть в той же Wi-Fi сети, что и компьютер,
#   в приложении указать http://<IP>:8080/version.json
#
# Режим -Tunnel: компьютер может быть где угодно, планшет тоже. Скрипт поднимет
#   публичный туннель и напечатает адрес вида https://xxx.trycloudflare.com.
#   ВАЖНО: адрес меняется при каждом запуске, и работать это будет только пока
#   открыто это окно.
#
# Остановить: Ctrl+C.

param(
    [int]$Port = 8080,
    [int]$VersionCode = 0,
    [string]$VersionName = "",
    [string]$Notes = "Тестовое обновление",
    [switch]$Tunnel
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$web = Join-Path $root 'dist\web'
$cloudflared = Join-Path (Split-Path -Parent $root) '.tools\cloudflared.exe'

if (-not (Test-Path $web)) { throw "Нет папки $web" }

$apk = Get-ChildItem $web -Filter *.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "В $web нет ни одного .apk — положите туда собранный APK" }

# ---- versionCode ---------------------------------------------------------
$manifest = Join-Path $web 'version.json'
if ($VersionCode -le 0) {
    $current = 0
    if (Test-Path $manifest) {
        try { $current = (Get-Content $manifest -Raw | ConvertFrom-Json).versionCode } catch { $current = 0 }
    }
    $VersionCode = [int]$current + 1
}
if (-not $VersionName) { $VersionName = "$VersionCode (тест)" }

$python = "$env:USERPROFILE\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if (-not (Test-Path $python)) { $python = 'python' }

$pyProc = $null
$cfProc = $null
$baseUrl = ''

try {
    # ---- локальный HTTP-сервер -------------------------------------------
    Write-Host 'Поднимаю локальный HTTP-сервер...' -ForegroundColor DarkGray
    $pyLog = Join-Path $env:TEMP 'ds-http-server.log'
    Remove-Item $pyLog -ErrorAction SilentlyContinue
    $pyProc = Start-Process -FilePath $python `
        -ArgumentList '-m', 'http.server', "$Port", '--bind', '0.0.0.0', '--directory', $web `
        -RedirectStandardError $pyLog -RedirectStandardOutput "$pyLog.out" `
        -PassThru -WindowStyle Hidden

    $listening = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 500
        if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
            $listening = $true
            break
        }
        if ($pyProc.HasExited) {
            $err = Get-Content $pyLog -Raw -ErrorAction SilentlyContinue
            throw "HTTP-сервер не запустился (код $($pyProc.ExitCode)). $err"
        }
    }
    if (-not $listening) { throw "Порт $Port так и не начал слушаться" }

    if ($Tunnel) {
        if (-not (Test-Path $cloudflared)) {
            throw "Не найден cloudflared: $cloudflared"
        }
        Write-Host 'Поднимаю публичный туннель Cloudflare (займёт ~10 секунд)...' -ForegroundColor DarkGray
        $log = Join-Path $env:TEMP 'ds-cloudflared.log'
        Remove-Item $log -ErrorAction SilentlyContinue
        $cfProc = Start-Process -FilePath $cloudflared `
            -ArgumentList 'tunnel', '--url', "http://127.0.0.1:$Port", '--no-autoupdate', `
                          '--protocol', 'http2', '--edge-ip-version', '4' `
            -RedirectStandardError $log -RedirectStandardOutput "$log.out" `
            -PassThru -WindowStyle Hidden

        for ($i = 0; $i -lt 80; $i++) {
            Start-Sleep -Milliseconds 500
            $text = (Get-Content $log -Raw -ErrorAction SilentlyContinue) +
                    (Get-Content "$log.out" -Raw -ErrorAction SilentlyContinue)
            if ($text -match 'https://[a-z0-9-]+\.trycloudflare\.com') { $baseUrl = $Matches[0]; break }
        }
        if (-not $baseUrl) { throw "Туннель не поднялся. Лог: $log" }
    }
    else {
        $ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object {
                $_.IPAddress -notlike '127.*' -and
                $_.IPAddress -notlike '169.254.*' -and
                $_.PrefixOrigin -ne 'WellKnown'
            } | Select-Object -ExpandProperty IPAddress -First 1)
        if (-not $ip) {
            $ip = ((ipconfig | Select-String 'IPv4' | Select-Object -First 1) -replace '.*:\s*', '').Trim()
        }
        if (-not $ip) { throw "Не удалось определить IP компьютера" }
        $baseUrl = "http://${ip}:${Port}"
    }

    # ---- version.json ------------------------------------------------------
    $json = [ordered]@{
        versionCode    = $VersionCode
        versionName    = $VersionName
        apkUrl         = "$baseUrl/$($apk.Name)"
        notes          = $Notes
        minVersionCode = 0
    }
    # UTF-8 БЕЗ BOM — org.json в приложении спотыкается на BOM.
    [System.IO.File]::WriteAllText(
        $manifest,
        ($json | ConvertTo-Json -Depth 4),
        (New-Object System.Text.UTF8Encoding($false))
    )

    $manifestUrl = "$baseUrl/version.json"

    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Cyan
    Write-Host '  ОБНОВЛЕНИЯ РАЗДАЮТСЯ С ЭТОГО КОМПЬЮТЕРА' -ForegroundColor Cyan
    Write-Host '------------------------------------------------------------'
    Write-Host "  APK:     $($apk.Name)"
    Write-Host "  Версия:  $VersionCode ($VersionName)"
    if ($Tunnel) {
        Write-Host '  Режим:   публичный туннель (подходит для разных сетей)' -ForegroundColor Green
    } else {
        Write-Host '  Режим:   локальная сеть (планшет должен быть в той же Wi-Fi)'
    }
    Write-Host ''
    Write-Host '  Впиши в приложении (Настройки → Адрес version.json):' -ForegroundColor Yellow
    Write-Host "     $manifestUrl" -ForegroundColor Green
    Write-Host ''
    if ($Tunnel) {
        Write-Host '  Ссылка живёт, пока открыто это окно. При перезапуске' -ForegroundColor DarkYellow
        Write-Host '  адрес изменится — впиши новый в приложении.' -ForegroundColor DarkYellow
        Write-Host ''
    }
    Write-Host '  Остановить: Ctrl+C' -ForegroundColor DarkGray
    Write-Host '============================================================' -ForegroundColor Cyan
    Write-Host ''

    while ($true) { Start-Sleep -Seconds 3 }
}
finally {
    if ($cfProc) { Stop-Process -Id $cfProc.Id -Force -ErrorAction SilentlyContinue }
    if ($pyProc) { Stop-Process -Id $pyProc.Id -Force -ErrorAction SilentlyContinue }
    Write-Host 'Сервер остановлен.' -ForegroundColor DarkGray
}
