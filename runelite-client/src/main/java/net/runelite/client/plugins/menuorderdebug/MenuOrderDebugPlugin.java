package net.runelite.client.plugins.menuorderdebug;

import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Side investigation tool - see MENU_ORDER_PID_INVESTIGATION.md at the repo root. Unrelated to any
 * specific plugin's feature set, deliberately standalone so it doesn't need to touch PvP Tools or
 * anything else.
 *
 * Question: when several players overlap on one tile, is the top-to-bottom order their options
 * appear in the right-click menu the same thing as combat "PID" (rotates every 100-150 ticks,
 * 40-60 on PvP worlds), or a separate, stable per-login value? Round 2 found a strong pattern in
 * real captured stacks: the lowest {@link Player#getId()} in a stack is listed FIRST, and every
 * other stacked player follows in strict DESCENDING getId() order below it - i.e. entry #1 looks
 * like "whichever player is actually under the cursor" and the rest is a separate, simpler sort.
 * `GameTick`'s own javadoc notes menus are built independent of the game tick ("occurrences that
 * take place purely on the client, such as right click menus, are independent of the game tick") -
 * a hint that whatever picks entry #1 is more likely a per-frame rendering/hit-test concern than
 * anything tied to the server-tick-driven combat PID. Round 3 instrumented accordingly: mouse
 * canvas position, camera angle/zoom, and each player's actual click hitbox ({@link
 * Player#getConvexHull()}) and its pixel distance from the cursor - plus, since none of that rules
 * out a PID-style rotation either, per-player "how long has this id been visible" / "how long since
 * it last moved" tick-based ages, and an automatic same-player-set rotation detector that flags in
 * the log the moment entry #1 changes for an otherwise-unchanged stack. Round 3 data also caught a
 * real methodological trap: a `MenuAction.WALK` ("Walk here") entry's resolved player is NOT
 * necessarily a stack member - it resolves to whichever player is nearest the clicked TILE, which
 * can be a different player on an adjacent tile entirely (confirmed via a mismatched WorldPoint and
 * hullContainsMouse=false on the offending entry, while its param0/param1 matched the mouse canvas
 * position exactly). WALK entries are now excluded from the pattern/rotation-check player set for
 * this reason - see the comment at that filter for the full story. With that fixed, the lowest-id-
 * first/descending-rest pattern held with zero exceptions across every round 2+3 event, including
 * one player set that stayed together (bar members leaving) and kept the same frontmost id for 48
 * consecutive ticks with zero rotation - consistent with "frontmost" being a fixed per-player
 * property (id-based render priority) rather than a PID-style rotating one, though that stretch is
 * still short of combat PID's documented 100-150 tick period, so isn't yet conclusive either way.
 */
@Slf4j
@PluginDescriptor(
	// No '/' in the name: PluginManagerTest writes one "<name>.dot" file per plugin, and a slash
	// turned that into a missing subdirectory - failing CI. The enabled/disabled setting is keyed
	// on the class name, so renaming doesn't reset it.
	name = "Menu Order PID Debug",
	description = "Investigation tool - logs right-click menu stacking order and full player/render state, to test what determines menu order on a stack",
	tags = {"debug", "developer", "pid", "menu", "investigation", "pvp"},
	enabledByDefault = false
)
public class MenuOrderDebugPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MenuOrderDebugOverlay overlay;

	// Session-long per-player bookkeeping, updated every GameTick - lets each menu-open event
	// report how long a given player has been visible and how long since they last moved, to test
	// a "most recently arrived/moved wins the front slot" hypothesis alongside the id-based one.
	private final Map<Integer, Integer> firstSeenTick = new HashMap<>();
	private final Map<Integer, Integer> lastMovedTick = new HashMap<>();
	private final Map<Integer, WorldPoint> lastKnownLocation = new HashMap<>();

	// Keyed by the exact sorted set of distinct player ids seen together in one menu - if that
	// exact same group shows up again with a different entry #1, that's a direct, automatic
	// rotation-boundary detector (no manual log-diffing required).
	private final Map<String, FrontmostObservation> frontmostHistoryByPlayerSet = new HashMap<>();

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		firstSeenTick.clear();
		lastMovedTick.clear();
		lastKnownLocation.clear();
		frontmostHistoryByPlayerSet.clear();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int tick = client.getTickCount();
		for (Player player : client.getPlayers())
		{
			int id = player.getId();
			firstSeenTick.putIfAbsent(id, tick);

			WorldPoint currentLocation = player.getWorldLocation();
			WorldPoint previousLocation = lastKnownLocation.get(id);
			if (previousLocation == null || !previousLocation.equals(currentLocation))
			{
				lastMovedTick.put(id, tick);
				lastKnownLocation.put(id, currentLocation);
			}
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		MenuEntry[] entries = event.getMenuEntries();

		// entries[] is stored bottom-to-top (the LAST element renders first, at the top) - per
		// MenuOpened's own javadoc. Walk it in reverse to get the actual on-screen, top-to-bottom
		// order.
		List<MenuEntry> playerEntries = new ArrayList<>();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (entry.getPlayer() != null)
			{
				playerEntries.add(entry);
			}
		}

		Point mouse = client.getMouseCanvasPosition();

		// Determine the modal WorldPoint among non-WALK player entries - the actual "which tile did
		// I click" ground truth for filtering to real stack members. WorldPoint equality, not
		// hullContainsMouse, turned out to be the robust signal: round 4's controlled 4-account
		// Attack test showed a genuine stack member can read hullContainsMouse=false on any given
		// frame (ordinary per-frame jitter in a tight stack) despite sharing the exact same
		// WorldPoint as the rest of the group - a hull-based filter would have wrongly dropped it.
		// An adjacent-tile leak (MillaMaster, KingLikeKong - round 4's other finding) still gets
		// excluded here, since its WorldPoint differs from the real stack's. MenuAction.WALK entries
		// are excluded from the vote entirely, since round 3 found their resolved player is chosen
		// by nearest-to-clicked-TILE, unrelated to the actual under-cursor stack.
		Map<WorldPoint, Integer> locationVotes = new HashMap<>();
		for (MenuEntry entry : playerEntries)
		{
			if (entry.getType() == MenuAction.WALK)
			{
				continue;
			}
			WorldPoint loc = entry.getPlayer().getWorldLocation();
			if (loc != null)
			{
				locationVotes.merge(loc, 1, Integer::sum);
			}
		}
		WorldPoint stackLocation = null;
		int bestVotes = 0;
		for (Map.Entry<WorldPoint, Integer> vote : locationVotes.entrySet())
		{
			if (vote.getValue() > bestVotes)
			{
				bestVotes = vote.getValue();
				stackLocation = vote.getKey();
			}
		}

		// First-seen order per distinct player, restricted to WALK-excluded entries whose player is
		// standing on stackLocation. Still logged in the raw per-entry dump below regardless of this
		// filter - only excluded from the pattern/rotation checks and the chat summary. A single
		// player having several menu options (Attack, Trade with, Report, ...) isn't a tile "stack"
		// either - only proceed once 2+ distinct same-tile players show up.
		Map<Integer, Player> distinctPlayersInOrder = new LinkedHashMap<>();
		for (MenuEntry entry : playerEntries)
		{
			if (entry.getType() == MenuAction.WALK)
			{
				continue;
			}
			Player player = entry.getPlayer();
			if (stackLocation == null || !stackLocation.equals(player.getWorldLocation()))
			{
				continue;
			}
			distinctPlayersInOrder.putIfAbsent(player.getId(), player);
		}

		if (distinctPlayersInOrder.size() < 2)
		{
			return;
		}

		// Where every currently-loaded player sits in client.getPlayers()' own iteration order -
		// an independent signal to compare against getId() and against the menu order below.
		Map<Integer, Integer> listPositionByPlayerId = new LinkedHashMap<>();
		int listPos = 0;
		for (Player p : client.getPlayers())
		{
			listPositionByPlayerId.put(p.getId(), listPos++);
		}

		Player localPlayer = client.getLocalPlayer();
		WorldPoint localLocation = localPlayer != null ? localPlayer.getWorldLocation() : null;
		int currentTick = client.getTickCount();

		log.info("=== Menu Order PID Debug: {} distinct players, tick={}, local='{}' id={} loc={}"
				+ " mouse={} cameraYaw={} cameraPitch={} zoom={} ===",
			distinctPlayersInOrder.size(), currentTick,
			localPlayer != null ? localPlayer.getName() : "?",
			localPlayer != null ? localPlayer.getId() : -1,
			localLocation, mouse, client.getCameraYaw(), client.getCameraPitch(), client.getScale());

		log.info("--- raw menu entries, on-screen top-to-bottom order ---");
		int rank = 1;
		for (MenuEntry entry : playerEntries)
		{
			Player player = entry.getPlayer();
			StringBuilder line = new StringBuilder();
			line.append("  #").append(rank++)
				.append(" type=").append(entry.getType())
				.append(" option='").append(entry.getOption()).append('\'')
				.append(" target='").append(entry.getTarget()).append('\'')
				.append(" identifier=").append(entry.getIdentifier())
				.append(" param0=").append(entry.getParam0())
				.append(" param1=").append(entry.getParam1())
				.append(" worldViewId=").append(entry.getWorldViewId())
				.append(" deprioritized=").append(entry.isDeprioritized())
				.append(" -> resolvedPlayer id=").append(player.getId())
				.append(" name='").append(player.getName()).append('\'');
			log.info(line.toString());
		}

		// Tag the actual displayed menu with each player's id - once per distinct player, on the
		// first entry it appears on (whatever type that is, including a WALK entry, which is
		// itself informative: a mismatched id there vs. the group below it is a live, in-menu
		// confirmation of the WALK-targets-nearest-tile-not-cursor quirk found in round 3). Done
		// after the raw-entry logging above so the log keeps the client's original, untagged text.
		//
		// Kept deliberately short (plain "#1970", no color tag, no "id:" label): round 6 found the
		// original "<col=ffff00> [id:1970]" tag was long enough to push the entry #1's text past
		// whatever width OSRS's own top-option/hover text is capped at, truncating the player's
		// name. The base game's own "<col=ffffff>Name<col=ff0000>  (level-92)" already displays
		// fine at its normal length, so staying close to that length (rather than roughly doubling
		// it, as the old tag did) is the safe margin.
		Set<Integer> idTaggedOnMenu = new HashSet<>();
		for (MenuEntry entry : playerEntries)
		{
			Player player = entry.getPlayer();
			if (idTaggedOnMenu.add(player.getId()))
			{
				entry.setTarget(entry.getTarget() + " #" + player.getId());
			}
		}

		log.info("--- distinct players, first-seen (top-to-bottom) order, full attributes ---");
		rank = 1;
		for (Player player : distinctPlayersInOrder.values())
		{
			log.info(describePlayer(rank++, player, localLocation, mouse, currentTick, listPositionByPlayerId));
		}

		logPatternCheck(distinctPlayersInOrder);
		logRotationCheck(distinctPlayersInOrder, currentTick);

		StringBuilder chatMessage = new StringBuilder("Menu order (top->bottom): ");
		boolean first = true;
		for (Player player : distinctPlayersInOrder.values())
		{
			if (!first)
			{
				chatMessage.append(", ");
			}
			first = false;
			chatMessage.append(player.getName()).append(" (id=").append(player.getId()).append(')');
		}

		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", chatMessage.toString(), null);
	}

	/**
	 * Checks this event against the round 4 pattern - confirmed across 36/42 prior events (86%,
	 * rising further once round 4's hullContainsMouse filtering above is applied to the other 6):
	 * lowest getId() first, HIGHEST getId() second, then everything else strictly descending. A
	 * plain "lowest first, rest descending" (no distinct max-second step) is logged as a weaker,
	 * secondary match - still worth seeing, since it's what round 2/3 originally thought the rule
	 * was before this sharper shape emerged.
	 */
	private static void logPatternCheck(Map<Integer, Player> distinctPlayersInOrder)
	{
		List<Integer> idsInOrder = new ArrayList<>(distinctPlayersInOrder.keySet());
		int minId = Collections.min(idsInOrder);
		int maxId = Collections.max(idsInOrder);
		boolean firstIsMin = idsInOrder.get(0) == minId;
		boolean firstIsMax = idsInOrder.get(0) == maxId;
		boolean secondIsMax = idsInOrder.size() > 1 && idsInOrder.get(1) == maxId;

		boolean tailStrictDescending = true;
		for (int i = 1; i < idsInOrder.size() - 1; i++)
		{
			if (idsInOrder.get(i) <= idsInOrder.get(i + 1))
			{
				tailStrictDescending = false;
				break;
			}
		}

		boolean matchesMinMaxDescending = firstIsMin && idsInOrder.size() < 2 || (firstIsMin && secondIsMax
			&& isStrictlyDescending(idsInOrder, 2));

		log.info("  PATTERN CHECK: matchesMinMaxDescending={} firstIsMin={} firstIsMax={} secondIsMax={}"
				+ " tailStrictDescending={} idsInOrder={}",
			matchesMinMaxDescending, firstIsMin, firstIsMax, secondIsMax, tailStrictDescending, idsInOrder);
	}

	private static boolean isStrictlyDescending(List<Integer> values, int fromIndex)
	{
		for (int i = fromIndex; i < values.size() - 1; i++)
		{
			if (values.get(i) <= values.get(i + 1))
			{
				return false;
			}
		}
		return true;
	}


	/**
	 * If this exact set of distinct player ids has been seen together before, compares entry #1
	 * then vs now. A change here - for an otherwise-unchanged group of players - is the direct
	 * signature of a rotating selection (PID-like); no change across a long tick gap instead points
	 * at a fixed, stable per-player property (e.g. always-lowest-id, or a fixed spatial slot).
	 */
	private void logRotationCheck(Map<Integer, Player> distinctPlayersInOrder, int currentTick)
	{
		TreeSet<Integer> sortedIds = new TreeSet<>(distinctPlayersInOrder.keySet());
		String idSetKey = sortedIds.toString();
		int currentFrontmost = distinctPlayersInOrder.keySet().iterator().next();

		FrontmostObservation previous = frontmostHistoryByPlayerSet.get(idSetKey);
		if (previous != null)
		{
			if (previous.frontmostPlayerId != currentFrontmost)
			{
				log.info("  *** ROTATION: same player set {}, frontmost changed id={} (tick={}) -> id={}"
						+ " (tick={}), delta={} ticks ***",
					idSetKey, previous.frontmostPlayerId, previous.tick, currentFrontmost, currentTick,
					currentTick - previous.tick);
			}
			else
			{
				log.info("  frontmost STABLE for player set {}: still id={}, {} ticks since last seen",
					idSetKey, currentFrontmost, currentTick - previous.tick);
			}
		}

		frontmostHistoryByPlayerSet.put(idSetKey, new FrontmostObservation(currentFrontmost, currentTick));
	}

	private String describePlayer(int rank, Player player, WorldPoint localLocation, Point mouse,
		int currentTick, Map<Integer, Integer> listPositionByPlayerId)
	{
		int id = player.getId();
		WorldPoint loc = player.getWorldLocation();
		Actor interacting = player.getInteracting();
		HeadIcon overhead = player.getOverheadIcon();

		Shape hull = player.getConvexHull();
		boolean hullContainsMouse = false;
		String pixelDistToMouse = "n/a";
		if (hull != null && mouse != null)
		{
			hullContainsMouse = hull.contains(mouse.getX(), mouse.getY());
			Rectangle bounds = hull.getBounds();
			Point hullCentre = new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
			pixelDistToMouse = String.valueOf(mouse.distanceTo(hullCentre));
		}

		Integer firstSeen = firstSeenTick.get(id);
		Integer lastMoved = lastMovedTick.get(id);

		StringBuilder line = new StringBuilder();
		line.append("  #").append(rank)
			.append(" id=").append(id)
			.append(" listPos=").append(listPositionByPlayerId.get(id))
			.append(" name='").append(player.getName()).append('\'')
			.append(" combatLevel=").append(player.getCombatLevel())
			.append(" loc=").append(loc)
			.append(" distFromLocal=").append(localLocation != null && loc != null ? localLocation.distanceTo(loc) : -1)
			.append(" ageTicks=").append(firstSeen != null ? currentTick - firstSeen : -1)
			.append(" ticksSinceLastMove=").append(lastMoved != null ? currentTick - lastMoved : -1)
			.append(" hullContainsMouse=").append(hullContainsMouse)
			.append(" pixelDistToMouse=").append(pixelDistToMouse)
			.append(" orientation=").append(player.getOrientation())
			.append(" currentOrientation=").append(player.getCurrentOrientation())
			.append(" animation=").append(player.getAnimation())
			.append(" poseAnimation=").append(player.getPoseAnimation())
			.append(" health=").append(player.getHealthRatio()).append('/').append(player.getHealthScale())
			.append(" dead=").append(player.isDead())
			.append(" interacting='").append(interacting != null ? interacting.getName() : "none").append('\'')
			.append(" friend=").append(player.isFriend())
			.append(" fcMember=").append(player.isFriendsChatMember())
			.append(" clanMember=").append(player.isClanMember())
			.append(" team=").append(player.getTeam())
			.append(" skullIcon=").append(player.getSkullIcon())
			.append(" overheadIcon=").append(overhead);
		return line.toString();
	}

	private static final class FrontmostObservation
	{
		private final int frontmostPlayerId;
		private final int tick;

		private FrontmostObservation(int frontmostPlayerId, int tick)
		{
			this.frontmostPlayerId = frontmostPlayerId;
			this.tick = tick;
		}
	}
}
