@echo off
setlocal

REM New_Db-Field - stop Tomcat (CMD only)
set "ROOT_DIR=%~dp0.."
set "TC_VERSION=9.0.80"
set "TOMCAT_DIR=%ROOT_DIR%\.run\apache-tomcat-%TC_VERSION%"

if not exist "%TOMCAT_DIR%\bin\shutdown.bat" (
	echo Tomcat not found: "%TOMCAT_DIR%"
	exit /b 0
)

echo Stopping Tomcat ...
set "CATALINA_HOME=%TOMCAT_DIR%"
set "CATALINA_BASE=%TOMCAT_DIR%"
pushd "%TOMCAT_DIR%\bin"
call shutdown.bat
popd
exit /b %errorlevel%


