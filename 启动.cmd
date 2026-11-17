@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\start-all.ps1"
echo.
echo 这个窗口可以关掉，服务在后台运行。停止请双击 停止.cmd。
pause
