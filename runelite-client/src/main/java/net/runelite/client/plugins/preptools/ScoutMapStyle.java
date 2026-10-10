package net.runelite.client.plugins.preptools;

/**
 * Which map Scout attaches - see ScoutMap.
 */
public enum ScoutMapStyle
{
	DRAWN("Drawn map"),
	MINIMAP("Your minimap"),
	NONE("None");

	private final String label;

	ScoutMapStyle(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
