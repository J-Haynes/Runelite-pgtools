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

	// Distinct from BORDER_COLOR so the location currently being edited in the sidebar (before
	// Save is clicked) is visually unmistakable from the already-saved boxes around it.
	private static final Color PREVIEW_BORDER_COLOR = Color.CYAN;
	private static final Color PREVIEW_LABEL_COLOR = Color.CYAN;

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
			drawLocationBox(graphics, location, plane, BORDER_COLOR, LABEL_COLOR);
		}

		// Live preview of whatever's currently being typed into the Location Mapper's edit form -
		// see PrepToolsPanel's edit-row listeners, which push this on every keystroke/arrow-key
		// adjustment, and PrepToolsPlugin#updateLocationPreview()/#clearLocationPreview().
		WildernessLocation preview = plugin.getPreviewWildernessLocation();
		if (preview != null)
		{
			drawLocationBox(graphics, preview, plane, PREVIEW_BORDER_COLOR, PREVIEW_LABEL_COLOR);
		}

		return null;
	}

	private void drawLocationBox(Graphics2D graphics, WildernessLocation location, int plane,
		Color borderColor, Color labelColor)
	{
		if (location.plane != plane)
		{
			return;
		}

		int sizeX = location.maxX - location.minX + 1;
		int sizeY = location.maxY - location.minY + 1;

		// The true geometric centre of the box, in local-point units (1/128th of a tile) - NOT
		// the centre *tile* via integer (minX+maxX)/2, which truncates to the wrong tile whenever
		// the box spans an even number of tiles on an axis. That truncation shifted the whole
		// drawn box half a tile west/south of where it should be, so e.g. the east edge cut
		// through the middle of the outer (maxX) tile instead of sitting on its outer edge.
		// Averaging the min/max tiles' own centre LocalPoints instead stays exact regardless of
		// parity - it lands on a half-tile point for an even-sized box, which LocalPoint (unlike
		// a WorldPoint tile) has no problem representing.
		LocalPoint minLocal = LocalPoint.fromWorld(client, new WorldPoint(location.minX, location.minY, plane));
		LocalPoint maxLocal = LocalPoint.fromWorld(client, new WorldPoint(location.maxX, location.maxY, plane));
		if (minLocal == null || maxLocal == null)
		{
			return;
		}

		LocalPoint centreLocal = new LocalPoint(
			(minLocal.getX() + maxLocal.getX()) / 2,
			(minLocal.getY() + maxLocal.getY()) / 2,
			minLocal.getWorldView());

		Polygon poly = Perspective.getCanvasTileAreaPoly(client, centreLocal, sizeX, sizeY, plane, 0);
		if (poly != null)
		{
			graphics.setColor(borderColor);
			graphics.drawPolygon(poly);
		}

		Point textLocation = Perspective.getCanvasTextLocation(client, graphics, centreLocal, location.name, 0);
		if (textLocation != null)
		{
			OverlayUtil.renderTextLocation(graphics, textLocation, location.name, labelColor);
		}
	}
}
