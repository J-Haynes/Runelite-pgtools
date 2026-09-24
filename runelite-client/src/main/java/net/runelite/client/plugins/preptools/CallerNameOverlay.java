package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws the name tag for every highlighted caller/target/pile hit, deliberately as a separate
 * Overlay from CallerTargetOverlay so it can use a different OverlayLayer. Per OverlayLayer's own
 * documentation, ABOVE_SCENE renders BELOW a player's overhead prayer icon, while UNDER_WIDGETS
 * renders above overheads (and below game interfaces) - so putting names on UNDER_WIDGETS is what
 * keeps a name from being hidden behind the prayer icon next to it. Font weight/size are
 * independently configurable per category (caller/target/pile) - see PrepToolsPlugin's
 * HighlightStyle and computeHighlightStyles() - same idea as each category's own configurable
 * outline weight in CallerTargetOverlay.
 *
 * Text antialiasing is explicitly enabled below (matching FreezeOverlay's rendering hints) -
 * without it, the base font only looks clean at its native ~16pt size, and scaling "Name size" up
 * or down makes the RuneScape TTF's edges look jagged/blocky instead of smoothly scaling.
 */
public class CallerNameOverlay extends Overlay
{
	private final HighlightTracker highlights;
	private final PrepToolsConfig config;

	@Inject
	private CallerNameOverlay(HighlightTracker highlights, PrepToolsConfig config)
	{
		this.highlights = highlights;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showNames())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

		Map<Player, HighlightStyle> styles = highlights.getStyles();

		for (Map.Entry<Player, HighlightStyle> entry : styles.entrySet())
		{
			HighlightStyle style = entry.getValue();
			graphics.setFont(graphics.getFont().deriveFont(style.fontStyle.getAwtStyle(), (float) style.fontSize));
			renderName(graphics, entry.getKey(), style.color);
		}

		return null;
	}

	private void renderName(Graphics2D graphics, Player player, Color color)
	{
		String name = config.preferNicknameDisplay()
			? highlights.getCallerNicknames().getOrDefault(player, player.getName())
			: player.getName();
		if (name == null)
		{
			return;
		}

		Point textLocation = player.getCanvasTextLocation(graphics, name, player.getLogicalHeight() + 40);
		if (textLocation != null)
		{
			OverlayUtil.renderTextLocation(graphics, textLocation, name, color);
		}
	}
}
