package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws a spell-specific icon and a countdown at the base of any player's model - including
 * your own - who is currently believed to be frozen, or in the brief post-freeze immunity
 * window. See PrepToolsPlugin#onGraphicChanged and #updateFreezeTimers for detection/tracking.
 *
 * Positioning technique matches the uploaded FrozenIconOverlay.class reference: the icon is
 * placed with Perspective.getCanvasImageLocation(), the purpose-built RuneLite API for
 * positioning an image at a world location that automatically centres based on the image's own
 * dimensions - this is more correct than manually re-deriving a centred position from text
 * metrics, which is what the previous version of this file did. The countdown text is then
 * anchored to the right of wherever the icon actually ended up.
 *
 * Sprites are fetched with the synchronous SpriteManager.getSprite(int, int) - also matching the
 * reference plugin - rather than the async callback + manual cache used previously. Sprites are
 * part of the client's core cache, so this resolves immediately in practice; no caching needed.
 *
 * Layer is ABOVE_WIDGETS - strictly higher than CallerTargetOverlay/CallerNameOverlay's
 * UNDER_WIDGETS - so the freeze icon and countdown always render on top of the caller/target
 * shell (or dot) and name tag, not underneath them, regardless of overlay registration order.
 */
public class FreezeOverlay extends Overlay
{
	// World-height offset (not a fixed pixel offset) used as the anchor point - this scales
	// correctly with camera distance/zoom, unlike a constant pixel value.
	private static final int ANCHOR_WORLD_OFFSET = 20;
	private static final int ICON_TEXT_GAP = 6;

	private final PrepToolsPlugin plugin;
	private final PrepToolsConfig config;
	private final Client client;
	private final SpriteManager spriteManager;

	@Inject
	private FreezeOverlay(PrepToolsPlugin plugin, PrepToolsConfig config, Client client, SpriteManager spriteManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.client = client;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showFreezeTimers())
		{
			return null;
		}

		Map<Player, PrepToolsPlugin.FreezeState> freezeStates = plugin.getFreezeStates();
		if (freezeStates.isEmpty())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

		Color freezeColor = config.freezeTimerColor();
		Color immuneColor = config.immunityTimerColor();
		FreezeTimeFormat format = config.freezeTimeFormat();
		long now = System.currentTimeMillis();
		int currentTick = client.getTickCount();

		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 18f));

		for (Map.Entry<Player, PrepToolsPlugin.FreezeState> entry : freezeStates.entrySet())
		{
			Player player = entry.getKey();
			PrepToolsPlugin.FreezeState state = entry.getValue();
			if (player == null)
			{
				continue;
			}

			String text;
			Color color;

			if (state.immune)
			{
				color = immuneColor;
				if (format == FreezeTimeFormat.TICKS)
				{
					int remainingTicks = state.immunityEndTick - currentTick;
					if (remainingTicks <= 0)
					{
						continue;
					}
					text = String.valueOf(remainingTicks);
				}
				else
				{
					double remainingSeconds = (PrepToolsPlugin.FREEZE_IMMUNITY_DURATION_MILLIS - (now - state.immunityStartTimeMillis)) / 1000.0;
					if (remainingSeconds <= 0)
					{
						continue;
					}
					text = String.format("%.1fs", remainingSeconds);
				}
			}
			else
			{
				color = freezeColor;
				if (format == FreezeTimeFormat.TICKS)
				{
					int remainingTicks = state.endTick - currentTick;
					if (remainingTicks <= 0)
					{
						continue;
					}
					text = String.valueOf(remainingTicks);
				}
				else
				{
					double remainingSeconds = (state.durationMillis - (now - state.startTimeMillis)) / 1000.0;
					if (remainingSeconds <= 0)
					{
						continue;
					}
					text = String.format("%.1fs", remainingSeconds);
				}
			}

			drawIconAndText(graphics, player, state.spriteId, text, color);
		}

		return null;
	}

	private void drawIconAndText(Graphics2D graphics, Player player, int spriteId, String text, Color color)
	{
		LocalPoint localPoint = player.getLocalLocation();
		if (localPoint == null)
		{
			return;
		}

		BufferedImage icon = spriteManager.getSprite(spriteId, 0);
		FontMetrics metrics = graphics.getFontMetrics();

		if (icon != null)
		{
			Point iconPoint = Perspective.getCanvasImageLocation(client, localPoint, icon, ANCHOR_WORLD_OFFSET);
			if (iconPoint == null)
			{
				return;
			}

			graphics.drawImage(icon, iconPoint.getX(), iconPoint.getY(), null);

			int textX = iconPoint.getX() + icon.getWidth() + ICON_TEXT_GAP;
			int iconCentreY = iconPoint.getY() + (icon.getHeight() / 2);
			int textBaselineY = iconCentreY + (metrics.getAscent() - metrics.getDescent()) / 2;

			drawOutlinedString(graphics, text, textX, textBaselineY, color);
		}
		else
		{
			// Sprite not resolved yet (very first frame or two) - still show the countdown
			// rather than nothing, using the actor-relative text position as a fallback.
			Point textLocation = player.getCanvasTextLocation(graphics, text, ANCHOR_WORLD_OFFSET);
			if (textLocation != null)
			{
				drawOutlinedString(graphics, text, textLocation.getX(), textLocation.getY(), color);
			}
		}
	}

	private void drawOutlinedString(Graphics2D graphics, String text, int x, int y, Color color)
	{
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(color);
		graphics.drawString(text, x, y);
	}
}
