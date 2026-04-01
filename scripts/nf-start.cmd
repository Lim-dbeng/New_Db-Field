@echo off
setlocal EnableDelayedExpansion

REM New_Db-Field - start Tomcat (CMD only)
REM Set UTF-8 encoding for console output - MUST be at the very top
chcp 65001 >nul 2>&1

REM Set console font to support Korean characters
REG ADD "HKCU\Console" /v FaceName /t REG_SZ /d "Consolas" /f >nul 2>&1
REG ADD "HKCU\Console" /v CodePage /t REG_DWORD /d 65001 /f >nul 2>&1

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."

call "%SCRIPT_DIR%nf-build.cmd"
if errorlevel 1 exit /b 1
call "%SCRIPT_DIR%nf-deploy.cmd"
if errorlevel 1 exit /b 1

set "TC_VERSION=9.0.80"
set "TOMCAT_DIR=%ROOT_DIR%\.run\apache-tomcat-%TC_VERSION%"

echo Starting Tomcat ...
set "CATALINA_HOME=%TOMCAT_DIR%"
set "CATALINA_BASE=%TOMCAT_DIR%"

REM Prefer bundled JDK 11 if present (to match class file version 55)
set "JDK11_DIR=%ROOT_DIR%\..\jdk-11.0.13"
if exist "%JDK11_DIR%\bin\java.exe" (
    set "JAVA_HOME=%JDK11_DIR%"
    set "JRE_HOME=%JDK11_DIR%"
)

REM Create/Update setenv.bat for UTF-8 encoding - force overwrite
set "SETENV_FILE=%TOMCAT_DIR%\bin\setenv.bat"
(
    echo @echo off
    echo REM UTF-8 encoding settings for Korean language support
    echo set JAVA_OPTS=%%JAVA_OPTS%% -Dfile.encoding=UTF-8 -Duser.language=ko -Duser.country=KR -Dsun.jnu.encoding=UTF-8
    echo set JAVA_OPTS=%%JAVA_OPTS%% -Dconsole.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8
) > "%SETENV_FILE%"


REM Update logging.properties to use UTF-8 for ConsoleHandler
set "LOGGING_PROPERTIES=%TOMCAT_DIR%\conf\logging.properties"
if exist "%LOGGING_PROPERTIES%" (
    REM Remove old ConsoleHandler encoding line if exists
    findstr /V /C:"java.util.logging.ConsoleHandler.encoding" "%LOGGING_PROPERTIES%" > "%LOGGING_PROPERTIES%.tmp"
    move /Y "%LOGGING_PROPERTIES%.tmp" "%LOGGING_PROPERTIES%" >nul 2>&1
    REM Add ConsoleHandler encoding setting
    echo. >> "%LOGGING_PROPERTIES%"
    echo java.util.logging.ConsoleHandler.encoding = UTF-8 >> "%LOGGING_PROPERTIES%"
)

REM Set JAVA_TOOL_OPTIONS for UTF-8 (works for all Java processes)
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Duser.language=ko -Duser.country=KR

REM Create a wrapper batch file that forces UTF-8
set "WRAPPER_FILE=%TOMCAT_DIR%\bin\startup-utf8.bat"
(
    echo @echo off
    echo chcp 65001 ^>nul 2^>^&1
    echo cd /d "%%~dp0"
    echo call startup.bat
) > "%WRAPPER_FILE%"

pushd "%TOMCAT_DIR%\bin"
call startup.bat
popd
exit /b %errorlevel%



