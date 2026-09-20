package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * Draws the highlight for callers, targets, and pile-finder hits, on UNDER_WIDGETS - the same
 * layer as CallerNameOverlay - so it renders above overhead icons but never covers an open
 * interface or the right-click menu. This used to be ALWAYS_ON_TOP, which drew over literally
 * everything including the right-click context menu; matching the name overlay's layer keeps the
 * two visually consistent and fixes that.
 *
 * Two highlight shapes, shared across every category (Overlay section - PrepToolsConfig#highlightShape,
 * carried per player on HighlightStyle#shape): the usual model outline "shell" (ModelOutlineRenderer,
 * as before), or an experimental "Blue Dot Robot" mode that instead draws a plain coloured dot at
 * the centre of the player's model - each player's own per-category colour (Callers/Target/Pile
 * Finder section), shared dot diameter via HighlightStyle#dotSize.
 */
public class CallerTargetOverlay extends Overlay
{
	// "Feather" controls the softness/blur of the outline edge; 0 = hard edge.
	private static final int OUTLINE_FEATHER = 2;

	private final PrepToolsPlugin plugin;
	private final PrepToolsConfig config;
	private final Client client;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	private CallerTargetOverlay(PrepToolsPlugin plugin, PrepToolsConfig config, Client client, ModelOutlineRenderer modelOutlineRenderer)
	{
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Map<Player, PrepToolsPlugin.HighlightStyle> highlights = plugin.computeHighlightStyles(config);

		for (Map.Entry<Player, PrepToolsPlugin.HighlightStyle> entry : highlights.entrySet())
		{
			PrepToolsPlugin.HighlightStyle style = entry.getValue();
			if (style.shape == HighlightShape.DOT)
			{
				drawDot(graphics, entry.getKey(), style.color, style.dotSize);
			}
			else
			{
				modelOutlineRenderer.drawOutline(entry.getKey(), style.outlineWidth, style.color, OUTLINE_FEATHER);
			}
		}

		return null;
	}

	/**
	 * "Blue Dot Robot" mode: a filled circle centred on the player's model, rather than an
	 * outline around it. Height offset of getLogicalHeight() / 2 - "half the model's height above
	 * its feet" - is the same idiom RuneLite's own PartyPlugin uses for centring an icon on a
	 * player (see PartyStatusOverlay#renderPlayerOverlay's image overload), rather than the
	 * "logicalHeight + 40" offset CallerNameOverlay/FreezeOverlay use to sit above the head.
	 */
	private void drawDot(Graphics2D graphics, Player player, Color color, int dotSize)
	{
		LocalPoint localPoint = player.getLocalLocation();
		if (localPoint == null)
		{
			return;
		}

		Point centre = Perspective.localToCanvas(client, localPoint, client.getPlane(), player.getLogicalHeight() / 2);
		if (centre == null)
		{
			return;
		}

		int diameter = Math.max(1, dotSize);
		int radius = diameter / 2;
		int x = centre.getX() - radius;
		int y = centre.getY() - radius;

		graphics.setColor(color);
		graphics.fillOval(x, y, diameter, diameter);
		graphics.setColor(Color.BLACK);
		graphics.drawOval(x, y, diameter, diameter);
	}
}
