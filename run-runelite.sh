#!/bin/bash
# Launches the locally built RuneLite client with 2x UI scaling.
cd "$(dirname "$0")"
export JDK_JAVA_OPTIONS="-Dsun.java2d.uiScale=2 -Dflatlaf.uiScale=2"
exec java -jar runelite-client/build/libs/client-1.12.40-SNAPSHOT-shaded.jar
