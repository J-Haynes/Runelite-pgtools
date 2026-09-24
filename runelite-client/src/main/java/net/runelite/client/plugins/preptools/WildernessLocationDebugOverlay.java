package net.runelite.client.plugins.preptools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * A helper for building PrepToolsPlugin's wilderness location list (the sidebar's "Location
 * Mapper" tool) - draws every saved area's outer border on the local player's current plane, with
 * its name labelled at the area's centre. Toggled by the Debug section's "Show/edit wilderness
 * locations" setting; deliberately basic (no fill, no colour options) since this is a temporary
 * internal mapping aid, not a player-facing feature.
 *
 * Borders go through WorldLines, so the visible part of a box draws even when a corner is outside
 * the loaded scene (LocalPoint.fromWorld() is null there, which once hid whole boxes).
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

	private final LocationTracker locations;
	private final PrepToolsConfig config;
	private final Client client;
	private final ConfigManager configManager;

	@Inject
	private WildernessLocationDebugOverlay(LocationTracker locations, PrepToolsConfig config, Client client, ConfigManager configManager)
	{
		this.locations = locations;
		this.config = config;
		this.client = client;
		this.configManager = configManager;
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
		int drawDistance = WorldLines.drawDistance(client, configManager);

		graphics.setStroke(new BasicStroke(BORDER_STROKE_WIDTH));
		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 16f));

		for (WildernessLocation location : locations.getStore().getLocations())
		{
			drawLocationBox(graphics, location, plane, drawDistance, BORDER_COLOR, LABEL_COLOR);
		}

		// Live preview of whatever's currently being typed into the Location Mapper's edit form -
		// see PrepToolsPanel's edit-row listeners, which push this on every keystroke/arrow-key
		// adjustment, and PrepToolsPlugin#updateLocationPreview()/#clearLocationPreview().
		WildernessLocation preview = locations.getPreview();
		if (preview != null)
		{
			drawLocationBox(graphics, preview, plane, drawDistance, PREVIEW_BORDER_COLOR, PREVIEW_LABEL_COLOR);
		}

		return null;
	}

	private void drawLocationBox(Graphics2D graphics, WildernessLocation location, int plane, int drawDistance,
		Color borderColor, Color labelColor)
	{
		if (location.plane != plane)
		{
			return;
		}

		int sizeX = location.maxX - location.minX + 1;
		int sizeY = location.maxY - location.minY + 1;
		WorldView wv = client.getTopLevelWorldView();

		// Rebuilt every frame rather than cached per scene: boxes are few and small, and the edit
		// preview changes on every keystroke anyway.
		graphics.setColor(borderColor);
		WorldLines.draw(graphics, client,
			WorldLines.toLocalLines(new Rectangle(location.minX, location.minY, sizeX, sizeY), wv), drawDistance);

		// Label at the box's true geometric centre (a half-tile point for an even-sized box).
		float centreLocalX = (location.minX + sizeX / 2f - wv.getBaseX()) * Perspective.LOCAL_TILE_SIZE;
		float centreLocalY = (location.minY + sizeY / 2f - wv.getBaseY()) * Perspective.LOCAL_TILE_SIZE;
		Point centre = WorldLines.project(client, wv, centreLocalX, centreLocalY, plane);
		if (centre != null)
		{
			FontMetrics metrics = graphics.getFontMetrics();
			Point textLocation = new Point(centre.getX() - metrics.stringWidth(location.name) / 2, centre.getY());
			OverlayUtil.renderTextLocation(graphics, textLocation, location.name, labelColor);
		}
	}
}
