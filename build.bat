@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  yuci_com build script (Java Swing + jSerialComm -> exe)
rem  Usage: build.bat          build jar and app-image
rem         build.bat jar      only build the jar (skip jpackage)
rem  Env  : set YUCI_JAVA=C:\path\to\jdk   to override the JDK
rem ============================================================

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

rem --- locate JDK ---
if not "%YUCI_JAVA%"=="" set "JDK=%YUCI_JAVA%"
if not defined JDK if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%D in ("C:\Program Files\Java\jdk-*") do set "JDK=%%D"
if not defined JDK (
  echo [ERROR] JDK 21+ not found. Install a JDK or set YUCI_JAVA to its folder.
  exit /b 1
)

set "JSC=%ROOT%\lib\jSerialComm-2.11.4.jar"
set "SRC=%ROOT%\src\YuciCom.java"
set "BUILD=%ROOT%\build"

if not exist "%JDK%\bin\javac.exe" (
  echo [ERROR] JDK not found at "%JDK%"
  exit /b 1
)
if not exist "%JSC%" (
  echo [ERROR] missing "%JSC%"
  exit /b 1
)

echo [1/5] clean
if exist "%BUILD%" rmdir /s /q "%BUILD%"
mkdir "%BUILD%\classes" 2>nul
mkdir "%BUILD%\jar" 2>nul

echo [2/5] compile
"%JDK%\bin\javac.exe" -encoding UTF-8 -nowarn -d "%BUILD%\classes" -cp "%JSC%" "%SRC%"
if errorlevel 1 (
  echo [ERROR] compile failed
  exit /b 1
)

echo [3/5] merge jSerialComm into fat jar
pushd "%BUILD%\classes"
"%JDK%\bin\jar.exe" xf "%JSC%"
if exist "META-INF\MANIFEST.MF" del /q "META-INF\MANIFEST.MF"
copy /y "%ROOT%\icon.png" "icon.png" >nul
popd

rem manifest needs Multi-Release because jSerialComm ships META-INF/versions
> "%BUILD%\manifest.txt" echo Main-Class: YuciCom
>>"%BUILD%\manifest.txt" echo Multi-Release: true
>>"%BUILD%\manifest.txt" echo Implementation-Title: yuci_com
>>"%BUILD%\manifest.txt" echo Implementation-Version: 2.1

pushd "%BUILD%\classes"
"%JDK%\bin\jar.exe" cfm "%BUILD%\jar\yuci_com.jar" "%BUILD%\manifest.txt" .
if errorlevel 1 (
  popd
  echo [ERROR] jar failed
  exit /b 1
)
popd

echo [4/5] verify jar
"%JDK%\bin\java.exe" -Dstdout.encoding=UTF-8 -cp "%BUILD%\jar\yuci_com.jar" YuciCom --selftest
if errorlevel 1 (
  echo [ERROR] self test failed
  exit /b 1
)

if /i "%~1"=="jar" (
  echo [5/5] skipped jpackage
  echo DONE: "%BUILD%\jar\yuci_com.jar"
  if "%YUCI_NOPAUSE%"=="" pause
  exit /b 0
)

echo [5/5] jpackage
if exist "%ROOT%\dist\yuci_com" rmdir /s /q "%ROOT%\dist\yuci_com"
if not exist "%ROOT%\dist" mkdir "%ROOT%\dist"
"%JDK%\bin\jpackage.exe" ^
  --type app-image ^
  --name yuci_com ^
  --input "%BUILD%\jar" ^
  --main-jar yuci_com.jar ^
  --main-class YuciCom ^
  --icon "%ROOT%\yuci_com.ico" ^
  --dest "%ROOT%\dist" ^
  --app-version 2.1 ^
  --vendor "yuci" ^
  --add-modules java.base,java.desktop,java.logging,jdk.unsupported ^
  --java-options "-Xmx512m" ^
  --java-options "-Dstdout.encoding=UTF-8" ^
  --java-options "-Dstderr.encoding=UTF-8"
if errorlevel 1 (
  echo [ERROR] jpackage failed
  exit /b 1
)

echo.
echo DONE: "%ROOT%\dist\yuci_com\yuci_com.exe"
if "%YUCI_NOPAUSE%"=="" pause
