package net.runelite.client.plugins.preptools;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

/**
 * Highlight Others: the name of every player not on your team over their head, like core Player
 * Indicators' "Highlight others" - optionally only those in your level range (AttackRangeFilter).
 * Same spot and font as CallerNameOverlay; a player that overlay already names is skipped.
 */
public class OthersOverlay extends Overlay
{
	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;
	private final HighlightTracker highlights;
	private final AttackRangeFilter range;

	@Inject
	private OthersOverlay(Client client, PrepToolsConfig config, TeamTracker team, HighlightTracker highlights,
		AttackRangeFilter range)
	{
		this.client = client;
		this.config = config;
		this.team = team;
		this.highlights = highlights;
		this.range = range;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightOthers())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setFont(graphics.getFont().deriveFont(config.nameFontStyle().getAwtStyle(), (float) config.nameFontSize()));

		// No bracket (outside the wilderness): everyone.
		Set<Player> inRange = config.othersOnlyInRange() && range.bracketApplies() ? range.getOthersInRange() : null;
		Map<Player, HighlightStyle> named = config.showNames() ? highlights.getStyles() : null;
		Player local = client.getLocalPlayer();
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local || player.getName() == null || team.isOwnTeam(player)
				|| (inRange != null && !inRange.contains(player)) || (named != null && named.containsKey(player)))
			{
				continue;
			}

			String name = Text.sanitize(player.getName());
			Point location = player.getCanvasTextLocation(graphics, name, player.getLogicalHeight() + 40);
			if (location != null)
			{
				OverlayUtil.renderTextLocation(graphics, location, name, config.othersColor());
			}
		}
		return null;
	}
}
