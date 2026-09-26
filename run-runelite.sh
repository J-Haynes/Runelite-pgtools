#!/bin/bash
# Launches the locally built RuneLite client with 2x UI scaling.
cd "$(dirname "$0")"
export JDK_JAVA_OPTIONS="-Dsun.java2d.uiScale=2 -Dflatlaf.uiScale=2"
# Newest shaded jar - not a fixed name, so an upstream version bump can't leave this launching
# a stale jar from the old version.
jar=$(ls -t runelite-client/build/libs/client-*-shaded.jar 2>/dev/null | head -n 1)
if [ -z "$jar" ]; then
	echo "No shaded client jar - run ./gradlew :client:shadowJar first" >&2
	exit 1
fi
exec java -jar "$jar"
