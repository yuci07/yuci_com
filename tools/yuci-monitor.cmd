@echo off
rem ---------------------------------------------------------------
rem  yuci_com serial monitor wrapper (for CLion / VSCode / cmd)
rem
rem  yuci-monitor.cmd                auto-detect port, capture 10s
rem  yuci-monitor.cmd -p COM5 -t 30  explicit port and duration
rem  yuci-monitor.cmd -x             HEX output
rem  yuci-monitor.cmd -o log.txt     also save to file
rem  yuci-monitor.cmd -g             open the GUI instead
rem  yuci-monitor.cmd -l             list serial ports
rem ---------------------------------------------------------------

setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1

set "EXE=%~dp0..\dist\yuci_com\yuci_com.exe"
if not exist "%EXE%" (
    echo [yuci] cannot find yuci_com.exe at "%EXE%"
    echo [yuci] build it first: build.bat
    exit /b 2
)

set "PORT="
set "BAUD=115200"
set "SECS=10"
set "HEX="
set "OUT="
set "GUI="

:parse
if "%~1"=="" goto after_parse
if /i "%~1"=="-p" (
    set "PORT=%~2"
    shift
    shift
    goto parse
)
if /i "%~1"=="-b" (
    set "BAUD=%~2"
    shift
    shift
    goto parse
)
if /i "%~1"=="-t" (
    set "SECS=%~2"
    shift
    shift
    goto parse
)
if /i "%~1"=="-x" (
    set "HEX=1"
    shift
    goto parse
)
if /i "%~1"=="-o" (
    set "OUT=%~2"
    shift
    shift
    goto parse
)
if /i "%~1"=="-g" (
    set "GUI=1"
    shift
    goto parse
)
if /i "%~1"=="-l" goto list
if /i "%~1"=="-h" goto usage
if /i "%~1"=="--help" goto usage
echo [yuci] unknown option: %~1
goto usage

:after_parse
if defined PORT goto run

rem --- auto detect: prefer a real USB-UART bridge, never guess among several ---
set "FIRST="
set "TOTAL=0"
for /f "usebackq tokens=1,*" %%a in (`"%EXE%" --list 2^>nul`) do (
    set "CAND=%%a"
    if /i "!CAND:~0,3!"=="COM" (
        set /a TOTAL+=1
        if not defined FIRST set "FIRST=%%a"
        if not defined PORT (
            echo %%b | findstr /i /c:"CH340" /c:"CH341" /c:"CH9102" /c:"CP210" /c:"FTDI" /c:"USB-SERIAL" /c:"USB Serial" /c:"Prolific" /c:"Silicon Labs" /c:"STLink" /c:"ST-Link" /c:"Virtual COM" /c:"Maple" >nul && set "PORT=%%a"
        )
    )
)

rem --- only one port on the machine? then it must be that one ---
if not defined PORT if "!TOTAL!"=="1" set "PORT=!FIRST!"

if not defined PORT (
    echo [yuci] found !TOTAL! serial ports, none looks like a USB-UART bridge.
    "%EXE%" --list
    echo [yuci] pick one explicitly, e.g.:  -p COM5
    exit /b 3
)

:run
if defined GUI (
    echo [yuci] opening GUI ...
    start "" "%EXE%"
    exit /b 0
)

set "ARGS=--headless --port %PORT% --baud %BAUD% --seconds %SECS%"
if defined HEX set ARGS=%ARGS% --hex
if defined OUT set ARGS=%ARGS% --out "%OUT%"

echo [yuci] port=%PORT% baud=%BAUD% time=%SECS%s hex=%HEX%
echo ---------------------------------------------------------------
"%EXE%" %ARGS%
set "RC=%ERRORLEVEL%"
echo ---------------------------------------------------------------

if defined OUT (
    if exist "%OUT%" (
        echo [yuci] saved to %OUT%
    )
)
exit /b %RC%

:list
"%EXE%" --list
exit /b 0

:usage
echo.
echo  yuci-monitor.cmd [-p COMx] [-b baud] [-t seconds] [-x] [-o file] [-g] [-l]
echo.
echo    -p COMx     serial port (default: auto-detect USB-UART)
echo    -b baud     baud rate  (default: 115200)
echo    -t seconds  capture duration (default: 10, 0 = until stopped)
echo    -x          show HEX instead of text
echo    -o file     also write the capture to a file
echo    -g          open the yuci_com GUI
echo    -l          list available serial ports
echo.
exit /b 1
