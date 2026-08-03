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
JAVAC_JVM_OPTIONS=()
while IFS= read -r option; do
  if [[ -n "$option" ]]; then
    JAVAC_JVM_OPTIONS+=("$option")
  fi
done <<< "$SCIP_JAVAC_LAUNCHER_JVM_OPTIONS"
NEW_JAVAC_OPTS="$SCIP_JAVAC_OPTIONS_PREFIX-$RANDOM"
GRAPH_JVM_OPTIONS=()
if [[ "$SCIP_GRAPH_ENABLED" == "true" ]]; then
  GRAPH_JVM_OPTIONS+=(
    "-Dscip.graph.enabled=true"
    "-Dscip.graph.root=$SCIP_GRAPH_ROOT"
    "-Dscip.graph.target=$SCIP_GRAPH_TARGET"
  )
fi

for arg in "$@"; do
  if [[ $arg == -J* ]]; then
    LAUNCHER_ARGS+=("$arg")
  fi
done

java \
  "-Dscip.errorpath=$SCIP_ERRORPATH" \
  "-Dscip.pluginpath=$SCIP_PLUGINPATH" \
  "-Dscip.sourceroot=$SCIP_SOURCEROOT" \
  "-Dscip.targetroot=$SCIP_TARGETROOT" \
  "${GRAPH_JVM_OPTIONS[@]}" \
  -Dscip.output="$NEW_JAVAC_OPTS" \
  "-Dscip.old-output=$SCIP_OLD_JAVAC_OPTS" \
  -classpath "$SCIP_PLUGINPATH" \
  org.scip_code.scip_java.javac.InjectScipOptions \
  "$@"

javac "${JAVAC_JVM_OPTIONS[@]}" "@$NEW_JAVAC_OPTS" "${LAUNCHER_ARGS[@]}"
