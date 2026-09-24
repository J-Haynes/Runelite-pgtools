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
 * Draws every highlighted player (HighlightTracker#getStyles()): a model outline, or with the
 * "Blue Dot Robot" shape a coloured dot at the model's centre. UNDER_WIDGETS, like
 * CallerNameOverlay - above overhead icons, never over an open interface or the right-click menu
 * (ALWAYS_ON_TOP drew over the menu).
 */
public class CallerTargetOverlay extends Overlay
{
	// "Feather" controls the softness/blur of the outline edge; 0 = hard edge.
	private static final int OUTLINE_FEATHER = 2;

	private final HighlightTracker highlights;
	private final PrepToolsConfig config;
	private final Client client;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	private CallerTargetOverlay(HighlightTracker highlights, PrepToolsConfig config, Client client, ModelOutlineRenderer modelOutlineRenderer)
	{
		this.highlights = highlights;
		this.config = config;
		this.client = client;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Map<Player, HighlightStyle> styles = highlights.getStyles();

		for (Map.Entry<Player, HighlightStyle> entry : styles.entrySet())
		{
			HighlightStyle style = entry.getValue();
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
