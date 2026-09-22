package net.runelite.client.plugins.preptools;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.Map;
import javax.inject.Inject;
import static net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;

/**
 * A small always-on panel listing nearby team cape numbers, same population and icon-grid layout
 * as vanilla RuneLite's "Team" plugin (TeamCapesOverlay - same item-ID formula, same
 * itemManager.getImage(id, count, true) quantity-stamped icon, same wrapping horizontal panel),
 * but with the user's own custom name (PrepToolsConfig#teamCapeNames) rendered as a second line
 * directly below any cape whose team number has one configured - an unnamed team number just shows
 * the plain icon, exactly like vanilla.
 */
public class TeamCapeOverlay extends OverlayPanel
{
	// Vertical gap between the cape icon and its name line, when a custom name is shown.
	private static final Point NAME_GAP = new Point(0, 2);

	private final PrepToolsPlugin plugin;
	private final PrepToolsConfig config;
	private final ItemManager itemManager;

	@Inject
	private TeamCapeOverlay(PrepToolsPlugin plugin, PrepToolsConfig config, ItemManager itemManager)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setWrap(true);
		panelComponent.setOrientation(ComponentOrientation.HORIZONTAL);
		addMenuEntry(RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, "Team Capes overlay");
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTeamCapeOverlay())
		{
			return null;
		}

		Map<Integer, Integer> teams = plugin.getTeamCapeCounts();
		if (teams.isEmpty())
		{
			return null;
		}

		int minimumCount = Math.max(1, config.minimumTeamCapeCount());
		boolean anyEntry = false;

		for (Map.Entry<Integer, Integer> team : teams.entrySet())
		{
			int teamNumber = team.getKey();
			int count = team.getValue();
			if (count < minimumCount)
			{
				continue;
			}

			int itemId = teamCapeItemId(teamNumber);
			ImageComponent capeImage = new ImageComponent(itemManager.getImage(itemId, count, true));

			String customName = plugin.getTeamCapeNames().get(teamNumber);
			if (customName == null)
			{
				panelComponent.getChildren().add(capeImage);
			}
			else
			{
				LineComponent nameLine = LineComponent.builder().left(customName).build();
				panelComponent.getChildren().add(SplitComponent.builder()
					.first(capeImage)
					.second(nameLine)
					.orientation(ComponentOrientation.VERTICAL)
					.gap(NAME_GAP)
					.build());
			}
			anyEntry = true;
		}

		if (!anyEntry)
		{
			return null;
		}

		return super.render(graphics);
	}

	/**
	 * Same team-number -> item-ID formula as vanilla RuneLite's TeamCapesOverlay: team capes 1-50
	 * are WILDERNESS_CAPE_1 + 2 per step, team capes above that use the "zero" cape range (3 per
	 * step) instead - two different in-game item ranges for historical reasons, not something this
	 * plugin controls.
	 *
	 * Static + package-visible so PrepToolsPanel can reuse it too (the "Capes" sidebar tab's icon -
	 * team cape items are the only real source of a colourful, recognisable cape icon; the generic
	 * equipment-slot sprite tried first rendered blank/placeholder, not an actual cape).
	 */
	static int teamCapeItemId(int teamNumber)
	{
		int teamcapeNumber = teamNumber - 1;
		if (teamcapeNumber < 50)
		{
			return 2 * teamcapeNumber + ItemID.WILDERNESS_CAPE_1;
		}
		return 3 * (teamcapeNumber - 50) + ItemID.WILDERNESS_CAPE_ZERO;
	}
}
