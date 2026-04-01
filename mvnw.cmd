@echo off
setlocal
REM Apache Maven Wrapper - runs Maven without requiring mvn in PATH
set "MAVEN_PROJECTBASEDIR=%~dp0"
set "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"
set "MAVEN_OPTS=%MAVEN_OPTS% -Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%"

if not exist "%WRAPPER_JAR%" (
  echo [mvnw] maven-wrapper.jar not found. Run: mvn wrapper:wrapper
  exit /b 1
)

if "%JAVA_HOME%"=="" (
  set "JAVA_EXE=java"
) else (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

"%JAVA_EXE%" %MAVEN_OPTS% -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
exit /b %errorlevel%
