#!/bin/bash
# Launches the locally built RuneLite client with 2x UI scaling.
#
#   ./run-runelite.sh            logs in with ~/.runelite/credentials.properties (if any)
#   ./run-runelite.sh 58xlpc     logs in with ~/.runelite/Accounts/58xlpc.properties
#
# The account file is used in place: the client reads the file named by the
# runelite.credentials.path system property (relative to ~/.runelite), and writes refreshed
# tokens back to that same file. JDK_JAVA_OPTIONS from the caller is kept (the RL Account Selector
# app in tools/rl-account-selector passes the account this way).
cd "$(dirname "$0")"
opts="-Dsun.java2d.uiScale=2 -Dflatlaf.uiScale=2"
if [ -n "$1" ]; then
	account="${1%.properties}"
	if [ ! -f "$HOME/.runelite/Accounts/$account.properties" ]; then
		echo "No account file $HOME/.runelite/Accounts/$account.properties" >&2
		exit 1
	fi
	opts="$opts \"-Drunelite.credentials.path=Accounts/$account.properties\""
fi
export JDK_JAVA_OPTIONS="$opts${JDK_JAVA_OPTIONS:+ $JDK_JAVA_OPTIONS}"
# Newest shaded jar - not a fixed name, so an upstream version bump can't leave this launching
# a stale jar from the old version.
jar=$(ls -t runelite-client/build/libs/client-*-shaded.jar 2>/dev/null | head -n 1)
if [ -z "$jar" ]; then
	echo "No shaded client jar - run ./gradlew :client:shadowJar first" >&2
	exit 1
fi
exec java -jar "$jar"
