@echo off
rem Root entry: graceful shutdown (calls /api/shutdown, then stops Java, then the dev server)
rem Actual logic lives in scripts\stop-all.ps1
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-all.ps1" %*
set RC=%ERRORLEVEL%
echo.
if %RC% neq 0 (
  echo [FAIL] stop-all exited with code %RC%
  echo        see _run\stop-all.log
) else (
  echo [OK] all services stopped ^(8081 / 8080 / 8000 free^)
)
echo.
pause
endlocal & exit /b %RC%
