@echo off
rem Windows wrapper: runs install.sh under Git Bash.
rem   install.cmd --targets com.example.one,com.example.two
setlocal
set "TARGET=%~dp0install.sh"

set "BASH="
for %%B in (bash.exe) do if not defined BASH set "BASH=%%~$PATH:B"
if not defined BASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"

if not defined BASH (
  echo ERROR: bash.exe not found. Install Git for Windows: https://git-scm.com
  exit /b 1
)

"%BASH%" "%TARGET%" %*
exit /b %ERRORLEVEL%
