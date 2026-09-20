package net.runelite.client.plugins.preptools;

import java.awt.Font;

/**
 * Font weight for caller/target name tags, selectable in config. Mirrors java.awt.Font's own
 * PLAIN/BOLD style constants so CallerNameOverlay can hand this straight to Font.deriveFont().
 */
public enum NameFontStyle
{
	PLAIN("Plain", Font.PLAIN),
	BOLD("Bold", Font.BOLD);

	private final String label;
	private final int awtStyle;

	NameFontStyle(String label, int awtStyle)
	{
		this.label = label;
		this.awtStyle = awtStyle;
	}

	public int getAwtStyle()
	{
		return awtStyle;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
