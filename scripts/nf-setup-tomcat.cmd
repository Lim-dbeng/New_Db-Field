@echo off
setlocal

REM New_Db-Field - setup Tomcat (CMD only)
set "SCRIPT_DIR=%~dp0"
set "TC_VERSION=9.0.80"
set "BASE_DIR=%~dp0..\.run"
set "TOMCAT_DIR=%BASE_DIR%\apache-tomcat-%TC_VERSION%"

if not exist "%BASE_DIR%" mkdir "%BASE_DIR%"
if exist "%TOMCAT_DIR%\bin\startup.bat" (
	echo Tomcat exists: "%TOMCAT_DIR%"
	call "%SCRIPT_DIR%nf-patch-tomcat-connector.cmd" "%TOMCAT_DIR%"
	goto :eof
)

set "ZIP_FILE=%BASE_DIR%\tomcat.zip"
set "URL=https://archive.apache.org/dist/tomcat/tomcat-9/v%TC_VERSION%/bin/apache-tomcat-%TC_VERSION%-windows-x64.zip"

echo Downloading Tomcat %TC_VERSION% ...
curl -L -o "%ZIP_FILE%" "%URL%"
if errorlevel 1 (
	echo Failed to download Tomcat. Ensure curl is available.
	exit /b 1
)

echo Extracting ...
tar -xf "%ZIP_FILE%" -C "%BASE_DIR%"
if errorlevel 1 (
	echo Extraction failed. Ensure 'tar' is available (Windows 10+).
	exit /b 1
)
del /q "%ZIP_FILE%"

echo Tomcat ready at "%TOMCAT_DIR%"
call "%SCRIPT_DIR%nf-patch-tomcat-connector.cmd" "%TOMCAT_DIR%"
exit /b 0


