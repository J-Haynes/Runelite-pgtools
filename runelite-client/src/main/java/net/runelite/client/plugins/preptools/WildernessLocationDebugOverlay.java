package net.runelite.client.plugins.preptools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * A helper for building PrepToolsPlugin's wilderness location list (the sidebar's "Location
 * Mapper" tool) - draws every saved area's outer border on the local player's current plane, with
 * its name labelled once at the area's centre tile. Toggled by the Debug section's "Show location
 * boxes" setting; deliberately basic (no fill, no per-tile labels, no colour options) since this
 * is a temporary internal mapping aid, not a player-facing feature.
 *
 * Each area's border is drawn as a single quadrilateral (Perspective#getCanvasTileAreaPoly, the
 * same primitive an AoE indicator would use) covering the whole box in one call, rather than
 * walking every border tile individually - far cheaper, at the cost of the quad being a flat
 * approximation across large height changes. Fine for a "functional, not pretty" tool. Both the
 * border and the label silently skip an area whose centre tile isn't within the currently loaded
 * scene (the same call that would otherwise draw them just returns null) - most user-mapped areas
 * are small enough that this only matters when you're nowhere near that area anyway.
 */
public class WildernessLocationDebugOverlay extends Overlay
{
	private static final Color BORDER_COLOR = Color.MAGENTA;
	private static final Color LABEL_COLOR = Color.WHITE;
	private static final float BORDER_STROKE_WIDTH = 3f;

	private final PrepToolsPlugin plugin;
	private final PrepToolsConfig config;
	private final Client client;

	@Inject
	private WildernessLocationDebugOverlay(PrepToolsPlugin plugin, PrepToolsConfig config, Client client)
	{
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showWildernessLocationOverlay())
		{
			return null;
		}

		int plane = client.getPlane();

		graphics.setStroke(new BasicStroke(BORDER_STROKE_WIDTH));
		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 16f));

		for (WildernessLocation location : plugin.getWildernessLocations())
		{
			if (location.plane != plane)
			{
				continue;
			}

			int sizeX = location.maxX - location.minX + 1;
			int sizeY = location.maxY - location.minY + 1;
			int centreX = (location.minX + location.maxX) / 2;
			int centreY = (location.minY + location.maxY) / 2;

			LocalPoint centreLocal = LocalPoint.fromWorld(client, new WorldPoint(centreX, centreY, plane));
			if (centreLocal == null)
			{
				continue;
			}

			Polygon poly = Perspective.getCanvasTileAreaPoly(client, centreLocal, sizeX, sizeY, plane, 0);
			if (poly != null)
			{
				graphics.setColor(BORDER_COLOR);
				graphics.drawPolygon(poly);
			}

			Point textLocation = Perspective.getCanvasTextLocation(client, graphics, centreLocal, location.name, 0);
			if (textLocation != null)
			{
				OverlayUtil.renderTextLocation(graphics, textLocation, location.name, LABEL_COLOR);
			}
		}

		return null;
	}
}
