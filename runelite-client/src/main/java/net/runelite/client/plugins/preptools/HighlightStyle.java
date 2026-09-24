package net.runelite.client.plugins.preptools;

import java.awt.Color;

/**
 * How one highlighted player is drawn. Only the colour differs per category (caller, target,
 * pile...); outline width, font and shape are shared settings (the config's Overlay section), so
 * every highlight looks consistent.
 */
class HighlightStyle
{
	final Color color;
	final int outlineWidth;
	final NameFontStyle fontStyle;
	final int fontSize;
	final HighlightShape shape;
	final int dotSize;

	HighlightStyle(Color color, int outlineWidth, NameFontStyle fontStyle, int fontSize, HighlightShape shape, int dotSize)
	{
		this.color = color;
		this.outlineWidth = outlineWidth;
		this.fontStyle = fontStyle;
		this.fontSize = fontSize;
		this.shape = shape;
		this.dotSize = dotSize;
	}

	HighlightStyle withColor(Color newColor)
	{
		return new HighlightStyle(newColor, outlineWidth, fontStyle, fontSize, shape, dotSize);
	}
}
