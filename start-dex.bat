@echo off
rem Lance le programme d'aide DeX sur le telephone (a refaire apres chaque redemarrage du telephone).
rem Il continue de tourner apres avoir debranche le cable.
setlocal
set ADB=%~dp0..\adb.exe
rem Telephone : USB en priorite, sinon adb Wi-Fi
set PHONE=R5CW51C5R8X
"%ADB%" -s %PHONE% get-state >nul 2>&1 || set PHONE=192.168.1.156:5555

for /f "tokens=2 delims=:" %%a in ('"%ADB%" -s %PHONE% shell pm path com.example.phone') do set APK=%%a
if "%APK%"=="" (
  echo Appli QuestMirror Phone introuvable sur %PHONE%.
  exit /b 1
)

rem Arrete une eventuelle instance precedente puis lance le helper detache (setsid + nohup)
rem (deux appels separes : sinon pkill tue aussi la commande qui lance le helper)
"%ADB%" -s %PHONE% shell "pkill -f com.example.phone.dex.[D]exHelper"
"%ADB%" -s %PHONE% shell "CLASSPATH=%APK% setsid nohup app_process / com.example.phone.dex.DexHelper > /data/local/tmp/questmirror-dex.log 2>&1 &"
ping -n 3 127.0.0.1 >nul
"%ADB%" -s %PHONE% shell cat /data/local/tmp/questmirror-dex.log
echo.
echo Programme d'aide DeX lance sur %PHONE% (port 27184). Ouvre le panneau DeX depuis le casque.
