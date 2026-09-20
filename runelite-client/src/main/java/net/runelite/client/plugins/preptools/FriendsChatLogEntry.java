package net.runelite.client.plugins.preptools;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * One line of the Friends Chat Log sidebar panel section - who did what, and when. Session-only
 * (never persisted) - see PrepToolsPlugin#friendsChatLog, a bounded FIFO deque, newest first.
 */
class FriendsChatLogEntry
{
	final String displayText;

	FriendsChatLogEntry(String playerName, String activity)
	{
		// Not static: SimpleDateFormat isn't thread-safe, and these are created rarely enough
		// (join/leave/death events only) that a fresh instance per entry costs nothing worth caching.
		SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
		this.displayText = "[" + timeFormat.format(new Date()) + "] " + playerName + " " + activity;
	}
}
