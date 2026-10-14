@echo off
setlocal
cd /d "%~dp0"
if exist "runtime\python.exe" (
    "runtime\python.exe" "start_ayaka_api.py" %*
) else (
    python "start_ayaka_api.py" %*
)
pause
