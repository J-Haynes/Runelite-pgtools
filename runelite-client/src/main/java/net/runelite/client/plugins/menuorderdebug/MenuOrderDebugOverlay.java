package net.runelite.client.plugins.menuorderdebug;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws every nearby player's {@link Player#getId()} above their head - see
 * MENU_ORDER_PID_INVESTIGATION.md at the repo root for what this is for. That doc confirmed
 * getId() is a stable per-login client-side index (Mod Ash, Feb 2023: used for render priority
 * when players stack on a tile), separate from the rotating combat "PID". The open question this
 * overlay exists to help answer empirically is whether right-click menu stacking order tracks
 * this stable index, or the rotating PID instead - stack several accounts on one tile, right-click,
 * and compare the on-screen numbers (top-to-bottom render/index order) against
 * MenuOrderDebugPlugin's logged menu order for the same moment.
 */
public class MenuOrderDebugOverlay extends Overlay
{
	private static final Color LOCAL_PLAYER_COLOR = Color.CYAN;
	private static final Color OTHER_PLAYER_COLOR = Color.WHITE;

	private final Client client;

	@Inject
	private MenuOrderDebugOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		Player localPlayer = client.getLocalPlayer();

		for (Player player : client.getPlayers())
		{
			String text = "id=" + player.getId();
			Point textLocation = player.getCanvasTextLocation(graphics, text, player.getLogicalHeight() + 40);
			if (textLocation != null)
			{
				Color color = player == localPlayer ? LOCAL_PLAYER_COLOR : OTHER_PLAYER_COLOR;
				OverlayUtil.renderTextLocation(graphics, textLocation, text, color);
			}
		}

		return null;
	}
}
