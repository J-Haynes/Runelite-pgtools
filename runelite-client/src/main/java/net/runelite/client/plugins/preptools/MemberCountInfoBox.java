package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.image.BufferedImage;
import lombok.Setter;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Nearby friends chat / clan member counter - same look as the vanilla Team plugin's own
 * MembersIndicator (white count over the chat's side-tab icon). See
 * PrepToolsPlugin#updateMemberCountInfoBoxes().
 */
class MemberCountInfoBox extends InfoBox
{
	private final String label;

	@Setter
	private int count;

	MemberCountInfoBox(BufferedImage image, Plugin plugin, String label)
	{
		super(image, plugin);
		this.label = label;
	}

	@Override
	public String getText()
	{
		return Integer.toString(count);
	}

	@Override
	public Color getTextColor()
	{
		return Color.WHITE;
	}

	@Override
	public String getTooltip()
	{
		return count + " " + label + " member(s) near you";
	}
}
