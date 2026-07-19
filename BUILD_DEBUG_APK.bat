@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title Stridulate Android v2.3.2 - Build Debug APK
color 0A

echo ================================================================
echo  STRIDULATE ANDROID v2.3.2 - BUILD DEBUG APK
echo ================================================================
echo.

echo NOTE: This is a work-in-progress proof of concept.
echo.

if not defined JAVA_HOME (
    if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
)
if not defined JAVA_HOME (
    if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
)
if not defined JAVA_HOME (
    where java >nul 2>nul
    if errorlevel 1 (
        echo ERROR: Java was not found.
        echo Install Android Studio or set JAVA_HOME to a JDK 17 installation.
        pause
        exit /b 1
    )
)

if not exist local.properties (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        for %%I in ("%LOCALAPPDATA%\Android\Sdk") do set "SDK_DIR=%%~fI"
        set "SDK_DIR=!SDK_DIR:\=/!"
        >local.properties echo sdk.dir=!SDK_DIR!
        echo Created local.properties for !SDK_DIR!
    ) else (
        echo ERROR: Android SDK was not found at %LOCALAPPDATA%\Android\Sdk
        echo Install the Android SDK through Android Studio, then run this BAT again.
        pause
        exit /b 1
    )
)

where python >nul 2>nul
if not errorlevel 1 (
    echo Running offline project verification...
    python verification\verify_project.py
    if errorlevel 1 (
        echo.
        echo VERIFICATION FAILED. The APK build was stopped to protect the model contract.
        pause
        exit /b 1
    )
    python verification\test_open_set_gate.py
    if errorlevel 1 (
        echo.
        echo OPEN-SET REGRESSION TEST FAILED.
        pause
        exit /b 1
    )
    echo.
)

echo Building the debug APK...
call gradlew.bat --no-daemon :app:assembleDebug
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Review the error above.
    pause
    exit /b 1
)

echo.
echo ================================================================
echo  BUILD SUCCESSFUL
echo ================================================================
echo.
echo APK:
echo %CD%\app\build\outputs\apk\debug\app-debug.apk
echo.
start "" "%CD%\app\build\outputs\apk\debug"
pause
