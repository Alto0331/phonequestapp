@echo off
rem Arrete le programme d'aide DeX (ferme le bureau DeX du casque).
setlocal
set ADB=%~dp0..\adb.exe
set PHONE=R5CW51C5R8X
"%ADB%" -s %PHONE% get-state >nul 2>&1 || set PHONE=192.168.1.156:5555
"%ADB%" -s %PHONE% shell "pkill -f com.example.phone.dex.[D]exHelper"
echo Programme d'aide DeX arrete.
