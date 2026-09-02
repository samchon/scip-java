#!/usr/bin/env bash
set -eu

: "${SCIP_ERRORPATH:?}"
: "${SCIP_JAVAC_LAUNCHER_JVM_OPTIONS:?}"
: "${SCIP_JAVAC_OPTIONS_PREFIX:?}"
: "${SCIP_OLD_JAVAC_OPTS:?}"
: "${SCIP_PLUGINPATH:?}"
: "${SCIP_SOURCEROOT:?}"
: "${SCIP_TARGETROOT:?}"
: "${SCIP_GRAPH_ENABLED:?}"

LAUNCHER_ARGS=()
HAS_LAUNCHER_ARGS=false
JAVAC_JVM_OPTIONS=()
while IFS= read -r option; do
  if [[ -n "$option" ]]; then
    JAVAC_JVM_OPTIONS+=("$option")
  fi
done <<< "$SCIP_JAVAC_LAUNCHER_JVM_OPTIONS"
NEW_JAVAC_OPTS="$SCIP_JAVAC_OPTIONS_PREFIX-$RANDOM"

for arg in "$@"; do
  if [[ $arg == -J* ]]; then
    LAUNCHER_ARGS+=("$arg")
    HAS_LAUNCHER_ARGS=true
  fi
done

if [[ "$SCIP_GRAPH_ENABLED" == "true" ]]; then
  java \
    "-Dscip.errorpath=$SCIP_ERRORPATH" \
    "-Dscip.pluginpath=$SCIP_PLUGINPATH" \
    "-Dscip.sourceroot=$SCIP_SOURCEROOT" \
    "-Dscip.targetroot=$SCIP_TARGETROOT" \
    "-Dscip.graph.enabled=true" \
    "-Dscip.graph.root=$SCIP_GRAPH_ROOT" \
    "-Dscip.graph.target=$SCIP_GRAPH_TARGET" \
    -Dscip.output="$NEW_JAVAC_OPTS" \
    "-Dscip.old-output=$SCIP_OLD_JAVAC_OPTS" \
    -classpath "$SCIP_PLUGINPATH" \
    org.scip_code.scip_java.javac.InjectScipOptions \
    "$@"
else
  # Do not expand a declared empty option array: Bash 3.2 treats it as unset
  # under `set -u`, which is the default shell on macOS.
  java \
    "-Dscip.errorpath=$SCIP_ERRORPATH" \
    "-Dscip.pluginpath=$SCIP_PLUGINPATH" \
    "-Dscip.sourceroot=$SCIP_SOURCEROOT" \
    "-Dscip.targetroot=$SCIP_TARGETROOT" \
    -Dscip.output="$NEW_JAVAC_OPTS" \
    "-Dscip.old-output=$SCIP_OLD_JAVAC_OPTS" \
    -classpath "$SCIP_PLUGINPATH" \
    org.scip_code.scip_java.javac.InjectScipOptions \
    "$@"
fi

if [[ "$HAS_LAUNCHER_ARGS" == "true" ]]; then
  javac "${JAVAC_JVM_OPTIONS[@]}" "@$NEW_JAVAC_OPTS" "${LAUNCHER_ARGS[@]}"
else
  # Bash 3.2 treats a declared empty array as unset under `set -u`.
  javac "${JAVAC_JVM_OPTIONS[@]}" "@$NEW_JAVAC_OPTS"
fi
