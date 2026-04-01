@echo off
setlocal

REM New_Db-Field - deploy webapp to local Tomcat (CMD only)
set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."

call "%SCRIPT_DIR%nf-setup-tomcat.cmd"
if errorlevel 1 exit /b 1

set "TC_VERSION=9.0.80"
set "TOMCAT_DIR=%ROOT_DIR%\.run\apache-tomcat-%TC_VERSION%"
set "APP_SRC=%ROOT_DIR%\src\main\webapp"
REM Deploy at server root context so the URL is http://localhost:8080/ (no /New_Db-Field)
set "CONTEXT_NAME=ROOT"
set "APP_DEST=%TOMCAT_DIR%\webapps\%CONTEXT_NAME%"

echo Deploying to "%APP_DEST%" ...
if exist "%APP_DEST%" rmdir /s /q "%APP_DEST%"
xcopy /e /i /y "%APP_SRC%" "%APP_DEST%"
if errorlevel 1 (
	echo Deploy failed.
	exit /b 1
)

REM Copy Matdash assets into served path
set "MAT_ASSETS_SRC=%ROOT_DIR%\Matdash\dist\assets"
set "MAT_ASSETS_DEST=%APP_DEST%\matdash\assets"
if exist "%MAT_ASSETS_SRC%" (
	echo Copying Matdash assets ...
	xcopy /e /i /y "%MAT_ASSETS_SRC%" "%MAT_ASSETS_DEST%"
) else (
	echo Matdash assets not found at "%MAT_ASSETS_SRC%". Skipping.
)

REM Remove legacy deployment folder if present to avoid duplicate context
if exist "%TOMCAT_DIR%\webapps\New_Db-Field" rmdir /s /q "%TOMCAT_DIR%\webapps\New_Db-Field"
echo Deploy completed.
exit /b 0