@echo off
setlocal
REM Tomcat HTTP Connector에 maxPostSize / maxSwallowSize 추가 (기본 2MB 초과 POST·multipart 대응)
REM 인자: TOMCAT_DIR (예: ...\.run\apache-tomcat-9.0.80)
if "%~1"=="" (
	echo Usage: %~nx0 TOMCAT_DIR
	exit /b 1
)
set "SCRIPT_DIR=%~dp0"
set "TOMCAT_DIR=%~1"
set "SERVER_XML=%TOMCAT_DIR%\conf\server.xml"
if not exist "%SERVER_XML%" (
	echo [nf-patch-tomcat-connector] server.xml not found: "%SERVER_XML%"
	exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%nf-patch-tomcat-connector.ps1" -ServerXml "%SERVER_XML%"
exit /b %errorlevel%
