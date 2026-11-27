@echo off
title BotHive Desktop v0.7.2
echo Starting BotHive Desktop PC Companion...
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python 3 not found. Please install Python 3.9+ first.
    pause
    exit /b 1
)
start "" "http://127.0.0.1:7718"
python "%~dp0bothive_pc.py" --port 7718
pause
