@echo off
REM Maven 의존성을 WEB-INF/lib로 복사 (GeoTools 등, SHP 변환용)
REM nf-build에서 mvn이 PATH에 없을 때 이 스크립트를 먼저 실행하세요.
set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
pushd "%ROOT_DIR%"
call mvn -q dependency:copy-dependencies -DoutputDirectory=src/main/webapp/WEB-INF/lib -DincludeScope=compile -Dmdep.useRepositoryLayout=false
if errorlevel 1 (
    echo Failed. Ensure Maven is in PATH.
    popd
    exit /b 1
)
popd
echo Dependencies copied to src/main/webapp/WEB-INF/lib
exit /b 0
