@echo off
setlocal EnableExtensions

REM New_Db-Field - deploy webapp to local Tomcat (CMD only)
REM DCIM/SHP 등 업로드 데이터는 복사하지 않음 (앱은 src\main\webapp 쪽을 직접 참조).
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
echo   (skipping large upload dirs: DCIM SHP SURVEY_HWP SURVEY_HWP_REFS)
if not exist "%APP_DEST%" mkdir "%APP_DEST%"

REM 증분 복사 — 전체 삭제 후 xcopy 하지 않음 (사진·SHP 수천 개 복사 방지)
robocopy "%APP_SRC%" "%APP_DEST%" /E /XD DCIM SHP SURVEY_HWP SURVEY_HWP_REFS /R:1 /W:1 /NFL /NDL /NJH /NJS /NP
set "RC=%ERRORLEVEL%"
if %RC% GEQ 8 (
	echo Deploy failed ^(robocopy exit %RC%^).
	exit /b 1
)

call :EnsureDataLink DCIM
call :EnsureDataLink SHP
call :EnsureDataLink SURVEY_HWP
call :EnsureDataLink SURVEY_HWP_REFS

REM Copy Matdash assets into served path
set "MAT_ASSETS_SRC=%ROOT_DIR%\Matdash\dist\assets"
set "MAT_ASSETS_DEST=%APP_DEST%\matdash\assets"
if exist "%MAT_ASSETS_SRC%" (
	echo Copying Matdash assets ...
	robocopy "%MAT_ASSETS_SRC%" "%MAT_ASSETS_DEST%" /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP >nul
) else (
	echo Matdash assets not found at "%MAT_ASSETS_SRC%". Skipping.
)

REM Remove legacy deployment folder if present to avoid duplicate context
if exist "%TOMCAT_DIR%\webapps\New_Db-Field" rmdir /s /q "%TOMCAT_DIR%\webapps\New_Db-Field"

REM 클래스 reload 트리거 — web.xml의 mtime을 갱신하면 Tomcat이 컨텍스트 재시작 (JVM은 안 죽음)
set "WEB_XML=%APP_DEST%\WEB-INF\web.xml"
if exist "%WEB_XML%" (
    powershell -NoProfile -Command "$f=Get-Item '%WEB_XML%'; $f.LastWriteTime=Get-Date" >nul 2>nul
    echo Touched web.xml ^- Tomcat will reload context within ~10 seconds ^(no JVM restart^)^.
)

echo Deploy completed.
exit /b 0

:EnsureDataLink
set "DIRNAME=%~1"
set "LINK=%APP_DEST%\%DIRNAME%"
set "TARGET=%APP_SRC%\%DIRNAME%"
if not exist "%TARGET%" mkdir "%TARGET%" 2>nul
if exist "%LINK%" exit /b 0
mklink /J "%LINK%" "%TARGET%" >nul 2>&1
if errorlevel 1 (
	echo   %DIRNAME%: link skipped ^(folder already present in Tomcat^).
) else (
	echo   %DIRNAME%: junction -^> "%TARGET%"
)
exit /b 0
