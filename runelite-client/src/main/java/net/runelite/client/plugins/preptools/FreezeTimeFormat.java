package net.runelite.client.plugins.preptools;

/**
 * Display format for the freeze countdown, selectable in config.
 */
public enum FreezeTimeFormat
{
	SECONDS("Seconds"),
	TICKS("Ticks");

	private final String label;

	FreezeTimeFormat(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
