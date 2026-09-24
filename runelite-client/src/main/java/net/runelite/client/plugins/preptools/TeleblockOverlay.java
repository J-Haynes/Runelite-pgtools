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
import net.runelite.api.SpriteID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A teleblock icon and countdown at the base of each tele-blocked player's model (you included),
 * positioned like FreezeOverlay. Both overlays share an anchor, so for a player who's also frozen
 * the teleblock row is drawn one row lower - freeze on top.
 */
public class TeleblockOverlay extends Overlay
{
	private static final int ANCHOR_WORLD_OFFSET = 20;
	private static final int ICON_TEXT_GAP = 6;
	private static final int STACKED_ROW_GAP = 2;

	private final FreezeTracker freezes;
	private final PrepToolsConfig config;
	private final Client client;
	private final SpriteManager spriteManager;

	@Inject
	private TeleblockOverlay(FreezeTracker freezes, PrepToolsConfig config, Client client, SpriteManager spriteManager)
	{
		this.freezes = freezes;
		this.config = config;
		this.client = client;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTeleblockTimers())
		{
			return null;
		}

		Map<Player, TeleblockState> states = freezes.getTeleblockStates();
		if (states.isEmpty())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

		Color color = config.teleblockTimerColor();
		FreezeTimeFormat format = config.teleblockTimeFormat();
		long now = System.currentTimeMillis();
		int currentTick = client.getTickCount();

		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 18f));

		Map<Player, FreezeState> freezeStates = config.showFreezeTimers()
			? freezes.getFreezeStates() : java.util.Collections.emptyMap();

		for (Map.Entry<Player, TeleblockState> entry : states.entrySet())
		{
			Player player = entry.getKey();
			TeleblockState state = entry.getValue();
			if (player == null)
			{
				continue;
			}

			String text;
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

			drawIconAndText(graphics, player, text, color, freezeStates.containsKey(player));
		}

		return null;
	}

	private void drawIconAndText(Graphics2D graphics, Player player, String text, Color color, boolean belowFreeze)
	{
		LocalPoint localPoint = player.getLocalLocation();
		if (localPoint == null)
		{
			return;
		}

		BufferedImage icon = spriteManager.getSprite(SpriteID.SPELL_TELE_BLOCK, 0);
		FontMetrics metrics = graphics.getFontMetrics();

		if (icon != null)
		{
			Point iconPoint = Perspective.getCanvasImageLocation(client, localPoint, icon, ANCHOR_WORLD_OFFSET);
			if (iconPoint == null)
			{
				return;
			}

			// Freeze icons are the same spellbook-sprite size as this one, so one of our own icon
			// heights is one freeze row.
			int iconY = iconPoint.getY() + (belowFreeze ? icon.getHeight() + STACKED_ROW_GAP : 0);

			graphics.drawImage(icon, iconPoint.getX(), iconY, null);

			int textX = iconPoint.getX() + icon.getWidth() + ICON_TEXT_GAP;
			int iconCentreY = iconY + (icon.getHeight() / 2);
			int textBaselineY = iconCentreY + (metrics.getAscent() - metrics.getDescent()) / 2;

			drawOutlinedString(graphics, text, textX, textBaselineY, color);
		}
		else
		{
			Point textLocation = player.getCanvasTextLocation(graphics, text, ANCHOR_WORLD_OFFSET);
			if (textLocation != null)
			{
				int rowOffset = belowFreeze ? metrics.getHeight() + STACKED_ROW_GAP : 0;
				drawOutlinedString(graphics, text, textLocation.getX(), textLocation.getY() + rowOffset, color);
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
