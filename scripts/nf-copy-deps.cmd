@echo off
REM Maven 의존성을 WEB-INF/lib로 복사 (GeoTools 등, SHP 변환용)
REM nf-build에서 mvn이 PATH에 없을 때 이 스크립트를 먼저 실행하세요.
set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
pushd "%ROOT_DIR%"
where mvn >nul 2>nul
if %errorlevel%==0 (
	call mvn -q dependency:copy-dependencies -DoutputDirectory=src/main/webapp/WEB-INF/lib -DincludeScope=compile -Dmdep.useRepositoryLayout=false
) else (
	if exist "%ROOT_DIR%\mvnw.cmd" (
		if not exist "%ROOT_DIR%\.mvn\wrapper\maven-wrapper.jar" (
			if not exist "%ROOT_DIR%\.mvn\wrapper" mkdir "%ROOT_DIR%\.mvn\wrapper"
			echo Downloading Maven Wrapper JAR ^(one-time^)...
			powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar' -OutFile '%ROOT_DIR%\.mvn\wrapper\maven-wrapper.jar' -UseBasicParsing } catch { exit 1 }"
			if errorlevel 1 (
				echo Could not download maven-wrapper.jar. Install Maven and add to PATH, or run: mvn -N wrapper:wrapper
				popd
				exit /b 1
			)
		)
		echo Maven not in PATH. Using project mvnw.cmd...
		call mvnw.cmd -q dependency:copy-dependencies -DoutputDirectory=src/main/webapp/WEB-INF/lib -DincludeScope=compile -Dmdep.useRepositoryLayout=false
	) else (
		echo Failed: neither "mvn" in PATH nor mvnw.cmd in project root.
		popd
		exit /b 1
	)
)
if errorlevel 1 (
	echo Failed. Install Maven ^(add to PATH^) or run from repo root where mvnw.cmd exists.
	popd
	exit /b 1
)
popd
echo Dependencies copied to src/main/webapp/WEB-INF/lib
exit /b 0
