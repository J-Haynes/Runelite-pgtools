package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws a distinct dot and name on the minimap for the current primary caller, so they can be
 * found even before (or without) being visible in the 3D game view.
 */
public class CallerMinimapOverlay extends Overlay
{
	private final PrepToolsPlugin plugin;
	private final PrepToolsConfig config;

	@Inject
	private CallerMinimapOverlay(PrepToolsPlugin plugin, PrepToolsConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player primary = plugin.getPrimaryCaller();
		if (primary == null)
		{
			return null;
		}

		Point minimapLocation = primary.getMinimapLocation();
		if (minimapLocation == null)
		{
			return null;
		}

		Color color = config.callerColor();
		OverlayUtil.renderMinimapLocation(graphics, minimapLocation, color);

		if (config.showNames())
		{
			String name = config.preferNicknameDisplay()
				? plugin.getCallerNicknames().getOrDefault(primary, primary.getName())
				: primary.getName();
			if (name != null)
			{
				int textWidth = graphics.getFontMetrics().stringWidth(name);
				int textX = minimapLocation.getX() - (textWidth / 2);
				int textY = minimapLocation.getY() - 8;

				graphics.setColor(Color.BLACK);
				graphics.drawString(name, textX + 1, textY + 1);
				graphics.setColor(color);
				graphics.drawString(name, textX, textY);
			}
		}

		return null;
	}
}
