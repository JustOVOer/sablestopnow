@echo off
REM ============================================================================
REM  Hull trace analyzer - detects "sinking below ground" and "velocity jumps"
REM
REM  Usage:
REM      tools\analyze-hull-trace.cmd
REM      tools\analyze-hull-trace.cmd run\sablestopnow-hull-trace.csv
REM
REM  Why this wrapper exists:
REM      This machine's PowerShell execution policy blocks .ps1 files
REM      (UnauthorizedAccess). The wrapper passes -ExecutionPolicy Bypass for
REM      this single invocation only; it does not change any system setting.
REM ============================================================================
setlocal
set SCRIPT=%~dp0analyze-hull-trace.ps1

where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
exit /b %ERRORLEVEL%
