@echo off
setlocal enabledelayedexpansion

echo ========================================
echo   PerformanceTesting - Startup Script
echo ========================================
echo.

:: --------------------- Project Directory ---------------------
cd /d "%~dp0"
set "PROJECT_DIR=%~dp0"

:: --------------------- Detect Java ---------------------

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
        echo [INFO] Using JAVA_HOME: !JAVA_HOME!
    )
)

if not defined JAVA_CMD (
    where java >nul 2>&1
    if !errorlevel! equ 0 (
        for /f "delims=" %%i in ('where java') do set "JAVA_CMD=%%i"
        echo [INFO] Using Java from PATH: !JAVA_CMD!
    )
)

if not defined JAVA_CMD (
    echo [ERROR] Java not found. Please install JDK 17+ and set JAVA_HOME or add to PATH
    pause
    exit /b 1
)

:: Check Java version
for /f "tokens=3" %%i in ('"!JAVA_CMD!" -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%i"
set "JAVA_VER=!JAVA_VER:"=!"
echo [INFO] Java version: !JAVA_VER!
echo.

:: --------------------- Detect Maven ---------------------

if exist "%PROJECT_DIR%mvnw.cmd" (
    set "MVN_CMD=%PROJECT_DIR%mvnw.cmd"
    echo [INFO] Using Maven Wrapper: mvnw.cmd
) else if exist "%PROJECT_DIR%mvnw" (
    set "MVN_CMD=%PROJECT_DIR%mvnw"
    echo [INFO] Using Maven Wrapper: mvnw
)

if not defined MVN_CMD (
    where mvn >nul 2>&1
    if !errorlevel! equ 0 (
        set "MVN_CMD=mvn"
        echo [INFO] Using system Maven
    )
)

if not defined MVN_CMD (
    echo [ERROR] Maven not found. Please install Maven or generate Maven Wrapper
    echo         Command: mvn wrapper:wrapper
    pause
    exit /b 1
)
echo.

:: --------------------- Load Config ---------------------

if exist "%PROJECT_DIR%startup-config.bat" (
    echo [INFO] Loading startup-config.bat ...
    call "%PROJECT_DIR%startup-config.bat"
)

:: --------------------- Compile and Start ---------------------
echo [INFO] Compiling project...
call !MVN_CMD! compile -q -f "%PROJECT_DIR%pom.xml"
if !errorlevel! neq 0 (
    echo [ERROR] Compilation failed. Check error messages above.
    pause
    exit /b 1
)
echo [INFO] Compilation complete
echo.

echo [INFO] Starting application...
echo ========================================
echo.

call !MVN_CMD! spring-boot:run -f "%PROJECT_DIR%pom.xml" !SPRING_PROFILES!

echo.
echo ========================================
echo   Application stopped
echo ========================================
pause
