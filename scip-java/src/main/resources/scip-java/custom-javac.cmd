@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul

set "NEW_JAVAC_OPTS=%SCIP_JAVAC_OPTIONS_PREFIX%-%RANDOM%-%RANDOM%"
set "GRAPH_JVM_OPTIONS="
if /I "%SCIP_GRAPH_ENABLED%"=="true" (
  set GRAPH_JVM_OPTIONS="-Dscip.graph.enabled=true" "-Dscip.graph.root=%SCIP_GRAPH_ROOT%" "-Dscip.graph.target=%SCIP_GRAPH_TARGET%"
)

java ^
  "-Dscip.errorpath=%SCIP_ERRORPATH%" ^
  "-Dscip.pluginpath=%SCIP_PLUGINPATH%" ^
  "-Dscip.sourceroot=%SCIP_SOURCEROOT%" ^
  "-Dscip.targetroot=%SCIP_TARGETROOT%" ^
  %GRAPH_JVM_OPTIONS% ^
  "-Dscip.output=%NEW_JAVAC_OPTS%" ^
  "-Dscip.old-output=%SCIP_OLD_JAVAC_OPTS%" ^
  -classpath "%SCIP_PLUGINPATH%" ^
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
javac %SCIP_JAVAC_LAUNCHER_JVM_OPTIONS_CMD% @"%NEW_JAVAC_OPTS%" %LAUNCHER_ARGS%
exit /b %errorlevel%
