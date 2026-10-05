package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Your current world number ("W318") over the minimap's world map button.
 */
public class WorldNumberOverlay extends Overlay
{
	private final Client client;
	private final PrepToolsConfig config;

	@Inject
	private WorldNumberOverlay(Client client, PrepToolsConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showWorldOnMapButton() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		Widget button = visible(client.getWidget(InterfaceID.Orbs.ORB_WORLDMAP));
		if (button == null)
		{
			button = visible(client.getWidget(InterfaceID.OrbsNomap.ORB_WORLDMAP));
		}
		if (button == null)
		{
			return null;
		}

		String text = "W" + client.getWorld();
		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics metrics = graphics.getFontMetrics();
		Rectangle bounds = button.getBounds();
		int x = bounds.x + (bounds.width - metrics.stringWidth(text)) / 2;
		int y = bounds.y + (bounds.height + metrics.getAscent()) / 2 - 1;
		OverlayUtil.renderTextLocation(graphics, new Point(x, y), text, Color.WHITE);
		return null;
	}

	private static Widget visible(Widget widget)
	{
		return widget != null && !widget.isHidden() ? widget : null;
	}
}
