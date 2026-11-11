@echo off
rem Root entry: launch the whole stack (Java 8080 + Python RAG 8000 + Vue 8081)
rem Actual logic lives in scripts\start-all.ps1
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-all.ps1" %*
set RC=%ERRORLEVEL%
echo.
if %RC% neq 0 (
  echo [FAIL] start-all exited with code %RC%
  echo        see _run\start-all.log  ^(and java.log / py.log / web.log^)
) else (
  echo [OK] all services are running ^(closing this window is safe^)
  echo      Web  http://localhost:8081
  echo      API  http://localhost:8080
  echo      RAG  http://127.0.0.1:8000
)
echo.
pause
endlocal & exit /b %RC%
