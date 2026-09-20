package net.runelite.client.plugins.preptools;

/**
 * How a highlighted caller/target/pile hit is drawn: the usual model outline "shell", or an
 * experimental dot at the centre of the player's model ("Blue Dot Robot" mode). Selectable in
 * config - see PrepToolsConfig#highlightShape and CallerTargetOverlay.
 */
public enum HighlightShape
{
	SHELL("Shell"),
	DOT("Blue Dot Robot");

	private final String label;

	HighlightShape(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
