@echo off
setlocal
where py >nul 2>nul
if %errorlevel%==0 (
  py -3 "%~dp0tools\ares_project.py" %*
) else (
  python "%~dp0tools\ares_project.py" %*
)
exit /b %errorlevel%
