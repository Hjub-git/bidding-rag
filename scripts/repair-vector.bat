@echo off
rem repair-vector.bat - vector db health check / repair (run this when you see hnsw errors)
rem usage: repair-vector.bat -DryRun  (check only) ; repair-vector.bat (repair if broken)
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0repair-vector.ps1" %*
set RC=%ERRORLEVEL%
echo.
if %RC% neq 0 (
  echo [FAIL] repair-vector exited with code %RC%  -- see _run\repair-vector.log in the repo root
) else (
  echo [OK] vector db healthy or repaired
)
echo.
pause
endlocal & exit /b %RC%