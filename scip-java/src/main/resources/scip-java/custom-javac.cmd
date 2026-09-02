@echo off
setlocal EnableExtensions DisableDelayedExpansion

set "NEW_JAVAC_OPTS=%SCIP_JAVAC_OPTIONS_PREFIX%-%RANDOM%-%RANDOM%"
set "SCIP_OUTPUT=%NEW_JAVAC_OPTS%"
if defined CLASSPATH (
  set "CLASSPATH=%SCIP_PLUGINPATH%;%CLASSPATH%"
) else (
  set "CLASSPATH=%SCIP_PLUGINPATH%"
)

java ^
  org.scip_code.scip_java.javac.InjectScipOptions ^
  %*
if errorlevel 1 exit /b %errorlevel%

set "LAUNCHER_ARGS="
:collect_launcher_args
if "%~1"=="" goto run_javac
set "ARG=%~1"
if "%ARG:~0,2%"=="-J" set "LAUNCHER_ARGS=%LAUNCHER_ARGS% "%~1""
shift
goto collect_launcher_args

:run_javac
set "JDK_JAVAC_OPTIONS=@"%NEW_JAVAC_OPTS%" %JDK_JAVAC_OPTIONS%"
javac %SCIP_JAVAC_LAUNCHER_JVM_OPTIONS_CMD% %LAUNCHER_ARGS%
exit /b %errorlevel%
