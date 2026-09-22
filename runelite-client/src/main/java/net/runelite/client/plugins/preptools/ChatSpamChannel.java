package net.runelite.client.plugins.preptools;

/**
 * Which chat channel the chat-spam and location-spam hotkeys actually submit to - shared between
 * both features since they're the same underlying mechanic (type a line, send it). See
 * PrepToolsPlugin#sendChatMessage() for the ScriptID.CHAT_SEND call this drives.
 */
public enum ChatSpamChannel
{
	FRIENDS_CHAT("Friends Chat"),
	CLAN_CHAT("Clan Chat");

	private final String label;

	ChatSpamChannel(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
