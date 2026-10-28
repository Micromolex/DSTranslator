@echo off
rem Starts the local update server (see tools\serve-updates.ps1).
rem
rem   serve-updates.cmd            - local Wi-Fi only
rem   serve-updates.cmd -Tunnel    - public Cloudflare link (works across countries)
rem
rem The wrapper is needed because:
rem   1) Windows blocks .ps1 execution by default;
rem   2) Windows PowerShell 5.1 reads .ps1 as ANSI unless the file has a UTF-8 BOM,
rem      so we make sure the BOM is there before running it.
setlocal
set "PS1=%~dp0tools\serve-updates.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='%PS1%';$b=[IO.File]::ReadAllBytes($p);if($b.Length -lt 3 -or $b[0] -ne 239 -or $b[1] -ne 187 -or $b[2] -ne 191){$t=[Text.Encoding]::UTF8.GetString($b);[IO.File]::WriteAllText($p,$t,(New-Object Text.UTF8Encoding($true)))}"
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
pause
