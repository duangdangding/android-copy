@echo off
rem 双击运行：启动所有已连接设备的 Shizuku 服务
powershell -ExecutionPolicy Bypass -File "%~dp0start-shizuku.ps1"
pause
