@echo off
setlocal

REM New_Db-Field - Build Java sources into WEB-INF/classes
set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "SRC_DIR=%ROOT_DIR%\src\main\java"
set "DEST_DIR=%ROOT_DIR%\src\main\webapp\WEB-INF\classes"

if not exist "%SRC_DIR%" (
	echo No java-src found. Skipping build.
	exit /b 0
)

if exist "%DEST_DIR%" rmdir /s /q "%DEST_DIR%"
mkdir "%DEST_DIR%"

REM GeoTools 등 Maven 의존성을 WEB-INF/lib로 복사 (SHP 변환용)
REM mvn이 PATH에 없으면 scripts\nf-copy-deps.cmd 를 먼저 실행하세요
set "LIB_DIR=%ROOT_DIR%\src\main\webapp\WEB-INF\lib"
if not exist "%LIB_DIR%" mkdir "%LIB_DIR%"
if exist "%ROOT_DIR%\pom.xml" (
	echo Resolving Maven dependencies to WEB-INF/lib...
	pushd "%ROOT_DIR%"
	call mvn -q dependency:copy-dependencies -DoutputDirectory=src/main/webapp/WEB-INF/lib -DincludeScope=compile -Dmdep.useRepositoryLayout=false 2>nul
	if errorlevel 1 (
		if exist "%ROOT_DIR%\mvnw.cmd" (
			call mvnw.cmd -q dependency:copy-dependencies -DoutputDirectory=src/main/webapp/WEB-INF/lib -DincludeScope=compile -Dmdep.useRepositoryLayout=false 2>nul
		)
	)
	if errorlevel 1 (
		if exist "%ROOT_DIR%\target\dependency\*.jar" (
			xcopy /Y /Q "%ROOT_DIR%\target\dependency\*" "%LIB_DIR%\" >nul 2>nul
		)
	)
	if errorlevel 1 (
		echo [nf-build] mvn not found or failed. Run scripts\nf-copy-deps.cmd manually if SHP conversion is needed.
	)
	popd
)

REM Resolve javac
set "JAVAC=javac"
if not exist "%JAVA_HOME%\bin\javac.exe" (
	for %%J in ("%ROOT_DIR%\..\jdk-11.0.13\bin\javac.exe") do (
		if exist "%%~fJ" set "JAVAC=%%~fJ"
	)
)

REM Resolve servlet api (Tomcat lib) for compilation
set "CATALINA_LIB="
if exist "%ROOT_DIR%\.run\apache-tomcat-9.0.80\lib\servlet-api.jar" set "CATALINA_LIB=%ROOT_DIR%\.run\apache-tomcat-9.0.80\lib"
if not defined CATALINA_LIB if exist "%ROOT_DIR%\..\apache-tomcat-9.0.65\lib\servlet-api.jar" set "CATALINA_LIB=%ROOT_DIR%\..\apache-tomcat-9.0.65\lib"
if not defined CATALINA_LIB if defined CATALINA_HOME if exist "%CATALINA_HOME%\lib\servlet-api.jar" set "CATALINA_LIB=%CATALINA_HOME%\lib"

set "CP=%ROOT_DIR%\src\main\webapp\WEB-INF\lib\*;%ROOT_DIR%\src\main\webapp\WEB-INF\classes"
if defined CATALINA_LIB set "CP=%CP%;%CATALINA_LIB%\*"

echo Compiling Java sources (Java 11)...
set "SRC_LIST=%TEMP%\ndf_java_sources_%RANDOM%.txt"
del /q "%SRC_LIST%" >nul 2>nul
for /r "%SRC_DIR%" %%f in (*.java) do (
	echo %%f>>"%SRC_LIST%"
)
if not exist "%SRC_LIST%" (
	echo No Java sources found.
) else (
	"%JAVAC%" -encoding UTF-8 -source 11 -target 11 -cp "%CP%" -d "%DEST_DIR%" @"%SRC_LIST%"
	if errorlevel 1 (
		echo Compilation failed.
		type "%SRC_LIST%"
		del /q "%SRC_LIST%" >nul 2>nul
		exit /b 1
	)
	del /q "%SRC_LIST%" >nul 2>nul
)
echo Build complete: "%DEST_DIR%"
exit /b 0