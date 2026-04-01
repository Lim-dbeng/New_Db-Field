@echo off
setlocal

set LIB_DIR=%~dp0..\webapp\WEB-INF\lib
set DRIVER_URL=https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.1/postgresql-42.7.1.jar
set DRIVER_FILE=postgresql-42.7.1.jar

echo Creating lib directory if it doesn't exist...
if not exist "%LIB_DIR%" mkdir "%LIB_DIR%"

echo Downloading PostgreSQL JDBC Driver...
echo URL: %DRIVER_URL%
echo Target: %LIB_DIR%\%DRIVER_FILE%

powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%DRIVER_URL%' -OutFile '%LIB_DIR%\%DRIVER_FILE%'}"

if exist "%LIB_DIR%\%DRIVER_FILE%" (
    echo.
    echo Success! PostgreSQL JDBC Driver downloaded to:
    echo %LIB_DIR%\%DRIVER_FILE%
) else (
    echo.
    echo ERROR: Download failed!
    echo Please manually download from:
    echo https://jdbc.postgresql.org/download.html
    echo And place it in: %LIB_DIR%
)

pause

