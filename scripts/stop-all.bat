@echo off
rem stop-all.bat - graceful shutdown (calls /api/shutdown, then stops Java and the web dev server)
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1" %*
set RC=%ERRORLEVEL%
echo.
if %RC% neq 0 (
  echo [FAIL] stop-all exited with code %RC%  -- see _run\stop-all.log in the repo root
) else (
  echo [OK] all services stopped ^(8081 / 8080 / 8000 should be free^)
)
echo.
pause
endlocal & exit /b %RC%