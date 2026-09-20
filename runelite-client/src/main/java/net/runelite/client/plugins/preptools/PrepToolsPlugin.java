package net.runelite.client.plugins.preptools;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.GraphicID;
import net.runelite.api.HeadIcon;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.api.Tile;
import net.runelite.api.VarClientStr;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.FriendsChatMemberJoined;
import net.runelite.api.events.FriendsChatMemberLeft;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.kit.KitType;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "PvP Tools",
	description = "Highlights the primary caller, their target, backup callers, FC-detected targeted players, and fight info",
	tags = {"highlight", "outline", "player", "pk", "pvp", "caller", "target", "interact", "weapon", "friends chat", "prayer"}
)
public class PrepToolsPlugin extends Plugin
{
	private static final String ADD_CALLER = "Add caller";
	private static final String ADD_TO_SNIPE_LIST = "Add to snipe list";

	/**
	 * Human death animations (forward/backward fall) - used to detect a friends chat member dying
	 * for the Friends Chat Log. Not exhaustive (a handful of unusual death variants exist, e.g.
	 * transformation deaths) but covers the overwhelming majority of PvP deaths.
	 */
	private static final Set<Integer> PLAYER_DEATH_ANIMATION_IDS = new HashSet<>(Arrays.asList(
		AnimationID.HUMAN_DEATH,
		AnimationID.HUMAN_DEATH_BACKWARDS
	));

	/**
	 * Teleblock duration, in game ticks: halved if the target was praying Protect from Magic at
	 * the moment it landed (confirmed via the OSRS wiki - 5 minutes normally, 2.5 minutes if
	 * protected).
	 */
	private static final int TELEBLOCK_DURATION_TICKS = 500;
	private static final int TELEBLOCK_DURATION_TICKS_PROTECTED = 250;

	/**
	 * Matches the wilderness level widget's text (e.g. "Level: 15") - same pattern
	 * CombatLevelPlugin uses to read InterfaceID.PvpIcons.WILDERNESSLEVEL, which is the game's own
	 * calculation rather than a coordinate-based formula this plugin would have to keep in sync
	 * with wilderness geometry itself.
	 */
	private static final Pattern WILDERNESS_LEVEL_PATTERN = Pattern.compile("^Level: (\\d+)$");

	private static final String WILDERNESS_LOCATIONS_CONFIG_KEY = "wildernessLocationsJson";

	/**
	 * Hard cap on a team cape's custom name length. Enforced twice: here at parse time (so it
	 * holds even if someone edits the raw "Team cape names" config field directly), and again as
	 * a keystroke-level DocumentFilter on the sidebar panel's own name field (PrepToolsPanel).
	 */
	static final int TEAM_CAPE_NAME_MAX_LENGTH = 5;

	// Melee weapons that count towards the melee indicator. All grouped under one generic melee icon.
	private static final Set<Integer> MELEE_WEAPON_IDS = new HashSet<>(Arrays.asList(
		1333,  // Rune scimitar
		12389, // Gilded scimitar
		23330, // Rune scimitar (guthix)
		23332, // Rune scimitar (saradomin)
		23334, // Rune scimitar (zamorak)
		1347   // Rune warhammer
	));

	// Ranged weapons that count towards the ranged indicator.
	private static final Set<Integer> RANGED_WEAPON_IDS = new HashSet<>(Arrays.asList(
		853 // Maple shortbow
	));

	// Freeze-capable spell graphics, their BASE duration in game ticks (1 tick = 0.6s), and the
	// spell's own icon sprite (for a distinct icon per spell type). Graphic IDs verified against
	// net.runelite.api.GraphicID; sprite IDs verified against net.runelite.api.SpriteID; durations
	// verified against the OSRS wiki. Does NOT account for gear that extends freeze duration
	// (swampbark armour for Bind/Snare/Entangle, ancient sceptre for Ice spells) - a countdown
	// hitting zero doesn't guarantee the target is actually free if they were using such gear.
	private static final Map<Integer, FreezeSpellInfo> FREEZE_SPELL_INFO = new HashMap<>();
	static
	{
		FREEZE_SPELL_INFO.put(GraphicID.BIND, new FreezeSpellInfo(8, SpriteID.SPELL_BIND));             // 4.8s
		FREEZE_SPELL_INFO.put(GraphicID.SNARE, new FreezeSpellInfo(16, SpriteID.SPELL_SNARE));           // 9.6s
		FREEZE_SPELL_INFO.put(GraphicID.ENTANGLE, new FreezeSpellInfo(24, SpriteID.SPELL_ENTANGLE));     // 14.4s
		FREEZE_SPELL_INFO.put(GraphicID.ICE_RUSH, new FreezeSpellInfo(8, SpriteID.SPELL_ICE_RUSH));      // 4.8s
		FREEZE_SPELL_INFO.put(GraphicID.ICE_BURST, new FreezeSpellInfo(16, SpriteID.SPELL_ICE_BURST));   // 9.6s
		FREEZE_SPELL_INFO.put(GraphicID.ICE_BLITZ, new FreezeSpellInfo(24, SpriteID.SPELL_ICE_BLITZ));   // 14.4s
		FREEZE_SPELL_INFO.put(GraphicID.ICE_BARRAGE, new FreezeSpellInfo(32, SpriteID.SPELL_ICE_BARRAGE)); // 19.2s
	}

	/**
	 * Menu option text for the same freeze-capable spells as FREEZE_SPELL_INFO above, for matching
	 * a manually-cast spell's right-click menu entry (only the spell's display name is available
	 * at that point - not a graphic ID, which only appears once the spell actually lands).
	 */
	private static final Set<String> FREEZE_SPELL_MENU_OPTIONS = new HashSet<>(Arrays.asList(
		"Bind", "Snare", "Entangle", "Ice Rush", "Ice Burst", "Ice Blitz", "Ice Barrage"
	));

	/**
	 * How many ticks after a freeze is first detected before the "did they move" early-clear
	 * check kicks in. See the note in updateFreezeTimers() for why this exists.
	 */
	private static final int MOVEMENT_CHECK_GRACE_TICKS = 3;

	/**
	 * How long a player is immune from being frozen again after a freeze wears off, per the OSRS
	 * wiki (confirmed for both Ice spells and Entangle - "5 ticks (3 seconds)").
	 */
	private static final int FREEZE_IMMUNITY_TICKS = 5;

	/**
	 * Millis version of the above, for FreezeOverlay's smooth wall-clock countdown display.
	 */
	static final long FREEZE_IMMUNITY_DURATION_MILLIS = FREEZE_IMMUNITY_TICKS * 600L;

	/**
	 * How fast the new-target flash blinks between the flash colour and the normal target colour,
	 * in computeHighlightColors(). Shared by both CallerTargetOverlay and CallerNameOverlay so
	 * their blinking stays in sync.
	 */
	private static final long TARGET_FLASH_BLINK_PERIOD_MILLIS = 200;

	/**
	 * Hard ceiling on how often onPostMenuSort's debug logging can fire, regardless of how often
	 * PostMenuSort itself fires - see the BUG FIX comment in onPostMenuSort for why this exists.
	 */
	private static final long MENU_DEBUG_LOG_INTERVAL_MILLIS = 1000;
	private long lastMenuDebugLogMillis;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ClientToolbar clientToolbar;

	private PrepToolsPanel panel;
	private NavigationButton navButton;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CallerTargetOverlay overlay;

	@Inject
	private CallerNameOverlay nameOverlay;

	@Inject
	private CallerMinimapOverlay minimapOverlay;

	@Inject
	private FightInfoOverlay fightInfoOverlay;

	@Inject
	private FreezeOverlay freezeOverlay;

	@Inject
	private TeleblockOverlay teleblockOverlay;

	@Inject
	private TeamCapeOverlay teamCapeOverlay;

	@Inject
	private WildernessLocationDebugOverlay wildernessLocationDebugOverlay;

	@Inject
	private PrepToolsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Gson gson;

	/**
	 * The current primary caller: the sidebar-pinned caller if one is on screen, otherwise
	 * the first on-screen caller in list order. Null if no configured caller is on screen.
	 */
	@Getter
	private Player primaryCaller;

	/**
	 * Players currently on the snipe list (PrepToolsConfig#snipeList) who are on screen - always
	 * highlighted, independent of caller/target/pile status. See rebuildHighlightStyles().
	 */
	@Getter
	private final Set<Player> snipedPlayers = new HashSet<>();

	/**
	 * Name of the caller manually pinned as primary via the sidebar panel (PrepToolsPanel).
	 * Session-only (not saved to config) - null means no manual pin, so primary falls back to
	 * plain list order. Cleared automatically if that name is removed from the Callers list.
	 */
	private String pinnedCallerName;

	/**
	 * Any other on-screen callers from the list who are not currently primary.
	 */
	@Getter
	private final Set<Player> backupCallers = new HashSet<>();

	/**
	 * Nickname overrides for caller players (primary or backup), keyed by Player. Only contains
	 * entries for callers whose config line included a ":Nickname" suffix.
	 */
	@Getter
	private final Map<Player, String> callerNicknames = new HashMap<>();

	/**
	 * Players (any player, including the local player) currently believed to be frozen, mapped
	 * to the details of that freeze (which spell, when it started/ends, for smooth countdown
	 * display).
	 */
	@Getter
	private final Map<Player, FreezeState> freezeStates = new HashMap<>();

	/**
	 * Last known position of each tracked frozen player, used to detect early movement (a
	 * genuinely frozen player cannot move at all, so movement means our tracked freeze - or its
	 * assumed duration - was wrong, and we should clear it immediately rather than show a stale
	 * countdown). Not consulted until MOVEMENT_CHECK_GRACE_TICKS after the freeze starts - see
	 * updateFreezeTimers() for why.
	 */
	private final Map<Player, WorldPoint> freezeLastKnownLocation = new HashMap<>();

	/**
	 * The player the primary caller is currently interacting with (attacking, following, etc).
	 * This always takes highlight priority over targetedPlayers - see CallerTargetOverlay.
	 */
	@Getter
	private final Set<Player> targets = new HashSet<>();

	/**
	 * Players outside your friends chat that enough of your friends chat members are piling on.
	 */
	@Getter
	private final Set<Player> targetedPlayers = new HashSet<>();

	/**
	 * Enemy players (not in your FC) currently engaged in the fight - either attacking an FC
	 * member, or being attacked by one. Feeds the opponent prayer/weapon breakdown.
	 */
	@Getter
	private final Set<Player> currentOpponents = new HashSet<>();

	/**
	 * Friends chat members who are not currently on screen (not within your loaded game view),
	 * in the same order they appear in the friends chat member list.
	 */
	@Getter
	private final List<String> missingFriendsChatMembers = new ArrayList<>();

	@Getter
	private int opponentMeleePrayerCount;
	@Getter
	private int opponentRangedPrayerCount;
	@Getter
	private int opponentMagicPrayerCount;
	@Getter
	private int opponentMeleeWeaponCount;
	@Getter
	private int opponentRangedWeaponCount;

	/**
	 * Number of players interacting with the local player who are wielding a tracked melee weapon.
	 */
	@Getter
	private int meleeAttackerCount;

	/**
	 * Number of players interacting with the local player who are wielding a tracked ranged weapon.
	 */
	@Getter
	private int rangedAttackerCount;

	// Tracks which players are currently within their "just became a target" flash window.
	private final Map<Player, Long> targetFlashEndTimes = new HashMap<>();
	private Set<Player> previousTargets = new HashSet<>();

	// Tracks the flash window for the "someone new is attacking you" cue on the fight info overlay.
	@Getter
	private long attackerFlashEndTime;
	private boolean previouslyBeingAttacked;

	/**
	 * Newest-first, size-capped log of friends chat join/leave/death activity - see
	 * logFriendsChatActivity(). Session-only, never persisted.
	 */
	@Getter
	private final Deque<FriendsChatLogEntry> friendsChatLog = new ArrayDeque<>();

	/**
	 * Team number -> nearby player count, recomputed every tick in updateTeamCapeCounts(). Sorted
	 * by count descending, same as vanilla RuneLite's Team plugin.
	 */
	@Getter
	private Map<Integer, Integer> teamCapeCounts = new LinkedHashMap<>();

	/**
	 * Team number -> user-configured custom name, parsed from PrepToolsConfig#teamCapeNames every
	 * tick (see updateTeamCapeCounts()) - same re-parse-every-tick approach already used for the
	 * Callers list (parseCallerEntries()), for consistency.
	 */
	@Getter
	private Map<Integer, String> teamCapeNames = new HashMap<>();

	/**
	 * Players (any player, including the local player) currently believed to be tele-blocked.
	 * Mirrors freezeStates/FreezeState in shape and lifecycle, minus the freeze-only concepts of
	 * post-expiry immunity and movement-based early clearing (neither applies to teleblock).
	 */
	@Getter
	private final Map<Player, TeleblockState> teleblockStates = new HashMap<>();

	/**
	 * User-built list of named wilderness areas (PrepToolsPanel's "Location Mapper" tool), used by
	 * findLocationName() to resolve the local player's current WorldPoint to a place name. Persisted
	 * as JSON via ConfigManager (see loadWildernessLocations()/saveWildernessLocations()) rather than
	 * a visible multi-line ConfigItem - editing a list of rectangles as raw text isn't something
	 * anyone wants to hand-type, so the panel's buttons are the only intended way to build it up.
	 * Also read by WildernessLocationDebugOverlay (the Debug section's "Show location boxes"
	 * toggle) to draw each saved area's border in-game.
	 */
	@Getter
	private List<WildernessLocation> wildernessLocations = new ArrayList<>();

	/**
	 * The local player's WorldPoint as of the most recent game tick - cached here (rather than read
	 * directly from the panel) since Player#getWorldLocation() must be called from the client
	 * thread, and the sidebar's "Set Corner A/B" buttons run on the EDT. Reading this plain,
	 * immutable-once-assigned field from the EDT is the same basic cross-thread reliance already
	 * used elsewhere in this plugin (e.g. missingFriendsChatMembers), not full synchronization -
	 * fine for a "best effort, refreshes every 0.6s" coordinate readout.
	 */
	@Getter
	private WorldPoint lastKnownWorldPoint;

	@Getter
	private String currentLocationName;

	@Getter
	private Integer currentWildernessLevel;

	/**
	 * The WorldPoint of the tile currently under the mouse cursor in the game view (null if the
	 * cursor isn't over a tile at all - e.g. over a widget, or off-screen). Purely informational,
	 * for the Location Mapper tool - unlike lastKnownWorldPoint, this is NOT used for the Set
	 * Corner A/B buttons, which still capture the player's own position.
	 */
	@Getter
	private WorldPoint hoveredWorldPoint;

	@Getter
	private String hoveredLocationName;

	// The two corners of the in-progress "new location" rectangle, set via the panel's Set Corner
	// A/B buttons. Session-only; cleared once saved (or on shutdown).
	private WorldPoint locationCornerA;
	private WorldPoint locationCornerB;

	/**
	 * True while the current Strength boost (boosted level minus real level) is above
	 * PrepToolsConfig#strengthGreenThreshold() - see updatePlayerStatBoosts().
	 */
	@Getter
	private boolean strengthBoosted;

	/**
	 * True while boosted Prayer points are above the real (unboosted) Prayer level.
	 */
	@Getter
	private boolean prayerBoosted;

	private final HotkeyListener chatSpamHotkeyListener = new HotkeyListener(() -> config.chatSpamHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			postFollowedPlayerNameToChat();
		}
	};

	private final HotkeyListener locationSpamHotkeyListener = new HotkeyListener(() -> config.locationSpamHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			postLocationToChat();
		}
	};

	@Provides
	PrepToolsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PrepToolsConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(nameOverlay);
		overlayManager.add(minimapOverlay);
		overlayManager.add(fightInfoOverlay);
		overlayManager.add(freezeOverlay);
		overlayManager.add(teleblockOverlay);
		overlayManager.add(teamCapeOverlay);
		overlayManager.add(wildernessLocationDebugOverlay);
		keyManager.registerKeyListener(chatSpamHotkeyListener);
		keyManager.registerKeyListener(locationSpamHotkeyListener);

		loadWildernessLocations();

		panel = new PrepToolsPanel(this);
		navButton = NavigationButton.builder()
			.tooltip("PvP Tools")
			.icon(createNavIcon())
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		panel.rebuild(parseCallerEntries(config.callers()));
		panel.rebuildSnipeList(parseNameList(config.snipeList()));
		panel.rebuildTeamCapeNames(parseTeamCapeNames(config.teamCapeNames()));
		panel.updateWildernessLocations(wildernessLocations);

		clearState();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(nameOverlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(fightInfoOverlay);
		overlayManager.remove(freezeOverlay);
		overlayManager.remove(teleblockOverlay);
		overlayManager.remove(teamCapeOverlay);
		overlayManager.remove(wildernessLocationDebugOverlay);
		keyManager.unregisterKeyListener(chatSpamHotkeyListener);
		keyManager.unregisterKeyListener(locationSpamHotkeyListener);
		clientToolbar.removeNavigation(navButton);
		clearState();
	}

	/**
	 * Draws a small, self-contained icon for the sidebar navigation button, rather than requiring
	 * a bundled image resource alongside these source files.
	 */
	private BufferedImage createNavIcon()
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.RED);
		g.fillOval(0, 0, 16, 16);
		g.setColor(Color.WHITE);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
		g.drawString("C", 5, 12);
		g.dispose();
		return icon;
	}

	private void clearState()
	{
		primaryCaller = null;
		backupCallers.clear();
		callerNicknames.clear();
		targets.clear();
		targetedPlayers.clear();
		currentOpponents.clear();
		freezeStates.clear();
		freezeLastKnownLocation.clear();
		targetFlashEndTimes.clear();
		previousTargets.clear();
		attackerFlashEndTime = 0;
		previouslyBeingAttacked = false;
		opponentMeleePrayerCount = 0;
		opponentRangedPrayerCount = 0;
		opponentMagicPrayerCount = 0;
		opponentMeleeWeaponCount = 0;
		opponentRangedWeaponCount = 0;
		meleeAttackerCount = 0;
		rangedAttackerCount = 0;
		cachedHighlightStyles = Collections.emptyMap();
		friendsChatMemberNamesCache = Collections.emptySet();
		snipedPlayers.clear();
		friendsChatLog.clear();
		teamCapeCounts = new LinkedHashMap<>();
		teamCapeNames = new HashMap<>();
		teleblockStates.clear();
		lastKnownWorldPoint = null;
		currentLocationName = null;
		currentWildernessLevel = null;
		hoveredWorldPoint = null;
		hoveredLocationName = null;
		locationCornerA = null;
		locationCornerB = null;
		strengthBoosted = false;
		prayerBoosted = false;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		refreshFriendsChatMemberNames();
		updateCallersAndTargets();
		updateSnipeList();
		updateTargetedPlayers();
		updateOpponentInfo();
		updateWeaponIndicators();
		updateMissingMembers();
		updateFreezeTimers();
		updateTeleblockTimers();
		updateTeamCapeCounts();
		updatePlayerStatBoosts();
		updateLocation();
		rebuildHighlightStyles(config);

		Set<String> onScreenStandardNames = new HashSet<>();
		for (Player player : backupCallers)
		{
			if (player.getName() != null)
			{
				onScreenStandardNames.add(Text.standardize(player.getName()));
			}
		}
		if (primaryCaller != null && primaryCaller.getName() != null)
		{
			onScreenStandardNames.add(Text.standardize(primaryCaller.getName()));
		}

		panel.updateStatus(onScreenStandardNames, primaryCaller != null ? Text.standardize(primaryCaller.getName()) : null);
		panel.updateMissingMembers(missingFriendsChatMembers);

		Set<String> onScreenSnipeNames = new HashSet<>();
		for (Player player : snipedPlayers)
		{
			if (player.getName() != null)
			{
				onScreenSnipeNames.add(Text.standardize(player.getName()));
			}
		}
		panel.updateSnipeStatus(onScreenSnipeNames);
		pushLocationMapperUpdate();
	}

	/**
	 * Rebuilds the sidebar's list of caller/snipe rows whenever the corresponding config text
	 * changes, so adding/removing/renaming an entry there is reflected without needing to restart
	 * anything.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PrepToolsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("callers".equals(event.getKey()))
		{
			panel.rebuild(parseCallerEntries(config.callers()));
		}
		else if ("snipeList".equals(event.getKey()))
		{
			panel.rebuildSnipeList(parseNameList(config.snipeList()));
		}
		else if ("teamCapeNames".equals(event.getKey()))
		{
			panel.rebuildTeamCapeNames(parseTeamCapeNames(config.teamCapeNames()));
		}
	}

	/**
	 * Called by the sidebar panel when the user selects a caller (or "Automatic") from the panel.
	 * Package-private since PrepToolsPanel lives in this same package.
	 */
	void setPinnedCaller(String name)
	{
		pinnedCallerName = name;
	}

	/**
	 * Called by the sidebar panel's reorder arrows to move a caller up or down in the "Callers"
	 * config list. Package-private since PrepToolsPanel lives in this same package.
	 *
	 * @param realName  the caller's real (non-nickname) name, as parsed into CallerEntry.name
	 * @param direction -1 to move up (earlier fallback priority), +1 to move down
	 */
	void moveCaller(String realName, int direction)
	{
		List<CallerEntry> entries = parseCallerEntries(config.callers());
		String standardizedName = Text.standardize(realName);

		int index = -1;
		for (int i = 0; i < entries.size(); i++)
		{
			if (Text.standardize(entries.get(i).name).equals(standardizedName))
			{
				index = i;
				break;
			}
		}

		if (index < 0)
		{
			return;
		}

		int newIndex = index + direction;
		if (newIndex < 0 || newIndex >= entries.size())
		{
			return;
		}

		Collections.swap(entries, index, newIndex);
		configManager.setConfiguration(PrepToolsConfig.GROUP, "callers", serializeCallerEntries(entries));
	}

	/**
	 * Called by the sidebar panel's delete button to remove a caller from the "Callers" config
	 * list entirely. Package-private since PrepToolsPanel lives in this same package.
	 *
	 * @param realName the caller's real (non-nickname) name, as parsed into CallerEntry.name
	 */
	void removeCaller(String realName)
	{
		List<CallerEntry> entries = parseCallerEntries(config.callers());
		String standardizedName = Text.standardize(realName);

		boolean removed = entries.removeIf(entry -> Text.standardize(entry.name).equals(standardizedName));
		if (!removed)
		{
			return;
		}

		// Drop a stale pin immediately rather than waiting for updateCallersAndTargets() to
		// notice next tick - onConfigChanged/panel.rebuild() runs before then and would otherwise
		// briefly show the just-deleted name still selected.
		if (pinnedCallerName != null && Text.standardize(pinnedCallerName).equals(standardizedName))
		{
			pinnedCallerName = null;
		}

		configManager.setConfiguration(PrepToolsConfig.GROUP, "callers", serializeCallerEntries(entries));
	}

	/**
	 * Renders a list of CallerEntry back into the "Callers" config field's text format - the
	 * inverse of parseCallerEntries(). Used by moveCaller()/removeCaller() to persist a change.
	 */
	private String serializeCallerEntries(List<CallerEntry> entries)
	{
		StringBuilder sb = new StringBuilder();
		for (CallerEntry entry : entries)
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append(entry.name);
			if (entry.nickname != null)
			{
				sb.append(':').append(entry.nickname);
			}
		}
		return sb.toString();
	}

	/**
	 * Whether the given player is on the local player's own team - a configured caller (primary
	 * or backup) or a friends chat member. Used to optionally exclude own-team players from the
	 * freeze timer overlay, since AoE freezes landing on teammates aren't useful information.
	 */
	private boolean isOwnTeamMember(Player player)
	{
		if (player == null || player.getName() == null)
		{
			return false;
		}

		if (player == primaryCaller || backupCallers.contains(player))
		{
			return true;
		}

		return getFriendsChatMemberNames().contains(Text.standardize(player.getName()));
	}

	/**
	 * Tick-cached result of rebuildHighlightStyles() - the "base" style for every currently
	 * highlighted player, before the per-frame new-target flash blink is applied. See
	 * computeHighlightStyles() for why this is cached instead of rebuilt on every render.
	 */
	private Map<Player, HighlightStyle> cachedHighlightStyles = Collections.emptyMap();

	/**
	 * Rebuilds cachedHighlightStyles: the display colour/weight/font/shape for every
	 * currently-highlighted player, including the priority order (backup caller < snipe list <
	 * pile finder < primary caller < target). Weight/font/shape/dot size are shared across every category (the
	 * Overlay section in PrepToolsConfig) - only colour stays per-category (Callers/Target/Pile
	 * Finder sections). This used to be five independently-configurable settings per category
	 * (fifteen settings total for the same three visual knobs), which turned out to be more
	 * flexible than anyone actually wanted and just made the config panel messy - colour is the one
	 * setting worth varying per category, so that's the one that stayed independent.
	 *
	 * Called once per game tick (see onGameTick) rather than every render frame - the underlying
	 * caller/target/pile membership only changes on a tick boundary anyway, so recomputing this
	 * per frame (previously done independently by both CallerTargetOverlay and CallerNameOverlay,
	 * i.e. twice per frame) was pure waste. Only the flash blink below genuinely needs per-frame
	 * freshness, so that's kept separate in computeHighlightStyles().
	 */
	private void rebuildHighlightStyles(PrepToolsConfig config)
	{
		Map<Player, HighlightStyle> highlights = new HashMap<>();

		int outlineWidth = config.outlineWidth();
		NameFontStyle fontStyle = config.nameFontStyle();
		int fontSize = config.nameFontSize();
		HighlightShape shape = config.highlightShape();
		int dotSize = config.dotSize();

		HighlightStyle backupStyle = new HighlightStyle(config.backupCallerColor(), outlineWidth, fontStyle, fontSize, shape, dotSize);
		for (Player player : backupCallers)
		{
			if (player != null)
			{
				highlights.put(player, backupStyle);
			}
		}

		HighlightStyle snipeStyle = new HighlightStyle(config.snipeColor(), outlineWidth, fontStyle, fontSize, shape, dotSize);
		for (Player player : snipedPlayers)
		{
			if (player != null)
			{
				highlights.put(player, snipeStyle);
			}
		}

		if (config.enableTargetedPlayerDetection())
		{
			HighlightStyle pileStyle = new HighlightStyle(config.targetedPlayerColor(), outlineWidth, fontStyle, fontSize, shape, dotSize);
			for (Player player : targetedPlayers)
			{
				if (player != null)
				{
					highlights.put(player, pileStyle);
				}
			}
		}

		if (primaryCaller != null)
		{
			highlights.put(primaryCaller, new HighlightStyle(config.callerColor(), outlineWidth, fontStyle, fontSize, shape, dotSize));
		}

		HighlightStyle targetStyle = new HighlightStyle(config.targetColor(), outlineWidth, fontStyle, fontSize, shape, dotSize);
		for (Player player : targets)
		{
			if (player != null)
			{
				highlights.put(player, targetStyle);
			}
		}

		cachedHighlightStyles = highlights;
	}

	/**
	 * Returns the tick-cached base highlight styles (see rebuildHighlightStyles()), with the
	 * new-target flash blink applied on top for the current frame. Only allocates a modified copy
	 * of the map on a frame where a target is actually mid-blink - otherwise returns the cached
	 * map directly, unmodified, with no allocation at all. Shared by CallerTargetOverlay (outlines
	 * or dots), CallerNameOverlay (name tags), and the menu entry recolouring in onClientTick.
	 */
	public Map<Player, HighlightStyle> computeHighlightStyles(PrepToolsConfig config)
	{
		if (targets.isEmpty())
		{
			return cachedHighlightStyles;
		}

		boolean anyFlashing = false;
		for (Player target : targets)
		{
			if (isFlashingTarget(target))
			{
				anyFlashing = true;
				break;
			}
		}

		if (!anyFlashing)
		{
			return cachedHighlightStyles;
		}

		boolean blinkOn = (System.currentTimeMillis() / TARGET_FLASH_BLINK_PERIOD_MILLIS) % 2 == 0;
		if (!blinkOn)
		{
			return cachedHighlightStyles;
		}

		Map<Player, HighlightStyle> highlights = new HashMap<>(cachedHighlightStyles);
		for (Player target : targets)
		{
			if (!isFlashingTarget(target))
			{
				continue;
			}

			HighlightStyle style = highlights.get(target);
			if (style != null)
			{
				highlights.put(target, new HighlightStyle(
					config.targetFlashColor(), style.outlineWidth, style.fontStyle, style.fontSize, style.shape, style.dotSize));
			}
		}

		return highlights;
	}

	/**
	 * The resolved outline colour/weight/font/shape for one highlighted player, as computed by
	 * rebuildHighlightStyles()/computeHighlightStyles(). Bundles what used to be several parallel
	 * per-category config lookups into a single value so CallerTargetOverlay and CallerNameOverlay
	 * don't each have to re-derive which category (caller/target/pile) a player belongs to.
	 */
	static class HighlightStyle
	{
		final Color color;
		final int outlineWidth;
		final NameFontStyle fontStyle;
		final int fontSize;
		final HighlightShape shape;
		final int dotSize;

		HighlightStyle(Color color, int outlineWidth, NameFontStyle fontStyle, int fontSize, HighlightShape shape, int dotSize)
		{
			this.color = color;
			this.outlineWidth = outlineWidth;
			this.fontStyle = fontStyle;
			this.fontSize = fontSize;
			this.shape = shape;
			this.dotSize = dotSize;
		}
	}

	/**
	 * Determines the primary caller, any backup callers, and the primary caller's target.
	 *
	 * Primary caller selection: if a caller has been pinned via the sidebar panel and that
	 * player is currently on screen, they are ALWAYS primary, regardless of list order.
	 * Otherwise, falls back to the first on-screen caller in list order. This means a pinned
	 * caller is primary while present, automatically falls back to the next appropriate caller
	 * while they're away, and automatically resumes primary status the moment they return. To
	 * hand primary status to someone else, click their name in the sidebar panel - no config
	 * editing needed, works mid-fight, and doesn't require finding them in a crowd.
	 *
	 * Also detects newly-appeared targets and starts their flash timer.
	 */
	private void updateCallersAndTargets()
	{
		primaryCaller = null;
		backupCallers.clear();
		callerNicknames.clear();
		targets.clear();

		List<CallerEntry> entries = parseCallerEntries(config.callers());
		if (!entries.isEmpty())
		{
			// Drop a stale pin if that caller was removed from the list.
			if (pinnedCallerName != null)
			{
				boolean stillConfigured = false;
				for (CallerEntry entry : entries)
				{
					if (Text.standardize(entry.name).equals(Text.standardize(pinnedCallerName)))
					{
						stillConfigured = true;
						break;
					}
				}
				if (!stillConfigured)
				{
					pinnedCallerName = null;
				}
			}

			Map<String, Player> onScreenByStandardName = new HashMap<>();
			for (Player player : client.getPlayers())
			{
				if (player == null || player.getName() == null)
				{
					continue;
				}

				String standardizedPlayerName = Text.standardize(player.getName());
				for (CallerEntry entry : entries)
				{
					if (Text.standardize(entry.name).equals(standardizedPlayerName))
					{
						onScreenByStandardName.put(standardizedPlayerName, player);
						break;
					}
				}
			}

			CallerEntry primaryEntry = null;

			// A pinned caller who's on screen always wins, regardless of list order.
			if (pinnedCallerName != null)
			{
				Player pinnedPlayer = onScreenByStandardName.get(Text.standardize(pinnedCallerName));
				if (pinnedPlayer != null)
				{
					primaryCaller = pinnedPlayer;
					for (CallerEntry entry : entries)
					{
						if (Text.standardize(entry.name).equals(Text.standardize(pinnedCallerName)))
						{
							primaryEntry = entry;
							break;
						}
					}
				}
			}

			// No pin, or the pinned caller isn't on screen - fall back to first on-screen caller in list order.
			if (primaryCaller == null)
			{
				for (CallerEntry entry : entries)
				{
					Player player = onScreenByStandardName.get(Text.standardize(entry.name));
					if (player != null)
					{
						primaryCaller = player;
						primaryEntry = entry;
						break;
					}
				}
			}

			if (primaryEntry != null && primaryEntry.nickname != null)
			{
				callerNicknames.put(primaryCaller, primaryEntry.nickname);
			}

			for (CallerEntry entry : entries)
			{
				Player player = onScreenByStandardName.get(Text.standardize(entry.name));
				if (player == null || player == primaryCaller)
				{
					continue;
				}

				backupCallers.add(player);
				if (entry.nickname != null)
				{
					callerNicknames.put(player, entry.nickname);
				}
			}

			if (primaryCaller != null)
			{
				Actor interacting = primaryCaller.getInteracting();
				if (interacting instanceof Player && interacting != primaryCaller)
				{
					targets.add((Player) interacting);
				}
			}
		}

		// Detect newly-appeared targets and start their flash window.
		long flashEnd = System.currentTimeMillis() + Math.max(0, config.targetFlashDurationMillis());
		for (Player target : targets)
		{
			if (!previousTargets.contains(target))
			{
				targetFlashEndTimes.put(target, flashEnd);
			}
		}

		// Clean up expired/stale entries so this map doesn't grow forever.
		long now = System.currentTimeMillis();
		targetFlashEndTimes.entrySet().removeIf(e -> e.getValue() < now);

		previousTargets = new HashSet<>(targets);
	}

	/**
	 * Populates snipedPlayers from the "Snipe list" config text - deliberately just a flat
	 * name-match, unlike updateCallersAndTargets()'s primary/backup/pin logic: every configured
	 * name that's on screen gets highlighted, all at once, all the time.
	 */
	private void updateSnipeList()
	{
		snipedPlayers.clear();

		List<String> names = parseNameList(config.snipeList());
		if (names.isEmpty())
		{
			return;
		}

		Set<String> standardizedNames = new HashSet<>();
		for (String name : names)
		{
			standardizedNames.add(Text.standardize(name));
		}

		for (Player player : client.getPlayers())
		{
			if (player != null && player.getName() != null && standardizedNames.contains(Text.standardize(player.getName())))
			{
				snipedPlayers.add(player);
			}
		}
	}

	/**
	 * Whether the given player is currently within its "just became a target" flash window.
	 */
	public boolean isFlashingTarget(Player player)
	{
		if (!config.showTargetFlash())
		{
			return false;
		}

		Long end = targetFlashEndTimes.get(player);
		return end != null && System.currentTimeMillis() < end;
	}

	/**
	 * Highlights a player who isn't in your friends chat if enough friends chat members
	 * (yourself included) are currently interacting with them at once.
	 */
	private void updateTargetedPlayers()
	{
		targetedPlayers.clear();

		if (!config.enableTargetedPlayerDetection())
		{
			return;
		}

		Set<String> fcNames = getFriendsChatMemberNames();
		if (fcNames.isEmpty())
		{
			return;
		}

		Map<Player, Integer> attackerCounts = new HashMap<>();

		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}

			if (!fcNames.contains(Text.standardize(player.getName())))
			{
				continue;
			}

			Actor interacting = player.getInteracting();
			if (!(interacting instanceof Player))
			{
				continue;
			}

			Player target = (Player) interacting;
			String targetName = target.getName();
			if (targetName != null && fcNames.contains(Text.standardize(targetName)))
			{
				// Don't count friends-chat-on-friends-chat interactions (e.g. sparring).
				continue;
			}

			// Callers are your own team regardless of whether they happen to be in your
			// friends chat - never count them as a pile target.
			if (target == primaryCaller || backupCallers.contains(target))
			{
				continue;
			}

			attackerCounts.merge(target, 1, Integer::sum);
		}

		int threshold = Math.max(1, config.targetedPlayerThreshold());
		for (Map.Entry<Player, Integer> entry : attackerCounts.entrySet())
		{
			if (entry.getValue() >= threshold)
			{
				targetedPlayers.add(entry.getKey());
			}
		}
	}

	/**
	 * Builds the set of enemy (non-FC) players currently on screen - simply anyone who is NOT
	 * in your friends chat (and not you) - and tallies their overhead prayers and weapon categories.
	 */
	private void updateOpponentInfo()
	{
		currentOpponents.clear();
		opponentMeleePrayerCount = 0;
		opponentRangedPrayerCount = 0;
		opponentMagicPrayerCount = 0;
		opponentMeleeWeaponCount = 0;
		opponentRangedWeaponCount = 0;

		if (!config.showFightInfoOverlay() || (!config.showOpponentPrayers() && !config.showOpponentWeapons()))
		{
			return;
		}

		Set<String> fcNames = getFriendsChatMemberNames();

		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}

			if (fcNames.contains(Text.standardize(player.getName())))
			{
				continue;
			}

			// Callers are your own team regardless of whether they happen to be in your
			// friends chat - never count them as an opponent.
			if (player == primaryCaller || backupCallers.contains(player))
			{
				continue;
			}

			currentOpponents.add(player);
		}

		for (Player opponent : currentOpponents)
		{
			HeadIcon headIcon = opponent.getOverheadIcon();
			if (headIcon == HeadIcon.MELEE)
			{
				opponentMeleePrayerCount++;
			}
			else if (headIcon == HeadIcon.RANGED)
			{
				opponentRangedPrayerCount++;
			}
			else if (headIcon == HeadIcon.MAGIC)
			{
				opponentMagicPrayerCount++;
			}

			int weaponId = getEquippedWeaponId(opponent);
			if (MELEE_WEAPON_IDS.contains(weaponId))
			{
				opponentMeleeWeaponCount++;
			}
			else if (RANGED_WEAPON_IDS.contains(weaponId))
			{
				opponentRangedWeaponCount++;
			}
		}
	}

	/**
	 * Builds the list of friends chat members who are NOT currently on screen (not within your
	 * loaded game view) - i.e. out of render range, in a different area, or logged out. This
	 * checks Friends Chat membership specifically; the plugin doesn't currently support Clan
	 * Chat rosters.
	 */
	private void updateMissingMembers()
	{
		missingFriendsChatMembers.clear();

		FriendsChatManager friendsChatManager = client.getFriendsChatManager();
		if (friendsChatManager == null)
		{
			return;
		}

		FriendsChatMember[] members = friendsChatManager.getMembers();
		if (members == null)
		{
			return;
		}

		Set<String> onScreenStandardNames = new HashSet<>();
		for (Player player : client.getPlayers())
		{
			if (player != null && player.getName() != null)
			{
				onScreenStandardNames.add(Text.standardize(player.getName()));
			}
		}

		Player local = client.getLocalPlayer();
		String localStandard = local != null && local.getName() != null ? Text.standardize(local.getName()) : null;

		for (FriendsChatMember member : members)
		{
			if (member == null || member.getName() == null)
			{
				continue;
			}

			String name = member.getName();
			String standard = Text.standardize(name);

			if (standard.equals(localStandard))
			{
				// Don't list yourself.
				continue;
			}

			if (!onScreenStandardNames.contains(standard))
			{
				// World is the one extra field FriendsChatMember actually exposes for someone not
				// in your loaded scene (no combat level, gear, etc. - that data simply isn't synced
				// to your client for a player you can't see) - and it's arguably more useful here
				// than combat level would be anyway, since it says WHERE to find them.
				missingFriendsChatMembers.add(name + " (W" + member.getWorld() + ")");
			}
		}
	}

	/**
	 * Tick-cached result of refreshFriendsChatMemberNames() - see getFriendsChatMemberNames().
	 */
	private Set<String> friendsChatMemberNamesCache = Collections.emptySet();

	/**
	 * Rebuilds friendsChatMemberNamesCache: the standardized (nbsp-normalized, lowercased) names
	 * of every friends chat member, plus your own name. Standardizing here - and everywhere else
	 * names are compared in this plugin - avoids a known RuneLite gotcha: different data sources
	 * (FriendsChatMember vs Player) can represent the same display name with a regular space vs. a
	 * non-breaking space (U+00A0), which makes naive string equality fail unpredictably.
	 *
	 * Called once per game tick (see onGameTick), before anything that reads it - this used to be
	 * rebuilt from scratch (a fresh HashSet plus a full scan of the friends chat member array) on
	 * every single call to getFriendsChatMemberNames(), including from isOwnTeamMember(), which
	 * onClientTick's menu handling can now call several times in a row (once per matching menu
	 * entry) while the menu is just being hovered - i.e. potentially many rebuilds per frame during
	 * exactly the moment this plugin matters most. Friends chat membership doesn't change faster
	 * than once a tick regardless, so there's nothing to gain from rebuilding it more often.
	 */
	private void refreshFriendsChatMemberNames()
	{
		Set<String> names = new HashSet<>();

		FriendsChatManager friendsChatManager = client.getFriendsChatManager();
		if (friendsChatManager != null)
		{
			FriendsChatMember[] members = friendsChatManager.getMembers();
			if (members != null)
			{
				for (FriendsChatMember member : members)
				{
					if (member != null && member.getName() != null)
					{
						names.add(Text.standardize(member.getName()));
					}
				}
			}
		}

		// Your own attacks should count towards the threshold too.
		Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			names.add(Text.standardize(local.getName()));
		}

		friendsChatMemberNamesCache = names;
	}

	private Set<String> getFriendsChatMemberNames()
	{
		return friendsChatMemberNamesCache;
	}

	/**
	 * Recolours "Attack" and manually-cast freeze spell menu options, prioritises "Attack" against
	 * the caller's target/a pile hit, and blocks a freeze spell against a teammate - all in one
	 * pass over the fully-built menu.
	 *
	 * BUG FIX (event source): this used to run on ClientTick, guarded by client.isMenuOpen(). That
	 * looked reasonable and matched PlayerIndicatorsPlugin's own onClientTick() pattern for a
	 * similar problem, but PostMenuSort's own javadoc is explicit that it exists for exactly this:
	 * "Posted after the menu is sorted, but before clicks are processed... shouldn't be used as a
	 * general purpose client tick event." ClientTick fires BEFORE the game's own internal menu sort
	 * runs, so any edit made there - reordering, removing, even a plain entry.setOption() recolour -
	 * was being silently overwritten by that later internal sort, which explains why teammate
	 * freeze-blocking kept appearing to do nothing no matter how the detection logic inside it was
	 * fixed: the detection was firing, the edits just never survived to be displayed or clicked.
	 * MenuEntrySwapperPlugin.onPostMenuSort() - RuneLite's own reference implementation for
	 * reordering/removing menu entries - confirms this is the correct hook, right down to also
	 * keeping the isMenuOpen() guard as a second safety net even though the event claims not to
	 * fire while the menu's open.
	 *
	 * REWRITE (was several onMenuEntryAdded handlers, one entry at a time): that approach had a
	 * second, independent problem. Deciding what's "default" one entry at a time meant a teammate's
	 * freeze spell entry could get Cancel/Walk here promoted to default AFTER an enemy target's
	 * Attack entry had already legitimately claimed that slot in the very same menu (e.g. a
	 * teammate and an enemy stacked on the same tile) - silently undoing the target's
	 * prioritisation, since only one entry can occupy the default slot. Working from the complete,
	 * already-sorted menu in one pass removes any dependency on event-ordering assumptions
	 * entirely - every entry is visible and categorised before any decision is made. It also runs
	 * the (mildly expensive) computeHighlightStyles() lookup once per menu instead of once per
	 * matching entry per event.
	 *
	 * Priority when deciding the default (last-index) entry: a tracked target/pile's Attack always
	 * wins if present; otherwise any other enemy's Attack wins; otherwise the array is left exactly
	 * as filtered, with nothing forced into the default slot. That last case matters while a spell
	 * is selected and a teammate happens to be nearby/stacked with an enemy: there's no "Attack"
	 * entry to prioritise at all in that situation (you're casting, not attacking), and the
	 * teammate's own freeze-spell entry has already been dropped from the array above - so
	 * whichever entry the game's own natural ordering already had in the default slot (typically
	 * the enemy's own spell-cast entry, since a selected spell/item is already high priority by
	 * default) is left untouched rather than second-guessed. An earlier version force-promoted
	 * "Cancel" here as an extra safety net, which broke exactly this case - reported directly as
	 * "I should be able to snare enemies through team members." Removing the teammate's entry is
	 * what makes it unclickable; nothing else needs to change for that to hold.
	 */
	@Subscribe
	public void onPostMenuSort(PostMenuSort postMenuSort)
	{
		if (client.isMenuOpen())
		{
			return;
		}

		MenuEntry[] menuEntries = client.getMenuEntries();
		if (menuEntries.length == 0)
		{
			return;
		}

		boolean colourOptions = config.colourAttackOption();
		boolean blockTeammateFreezes = config.preventFreezingTeammates();
		Map<Player, HighlightStyle> highlights = colourOptions ? computeHighlightStyles(config) : null;
		Player local = client.getLocalPlayer();
		String selectedSpellName = getSelectedSpellbookActionName();

		// BUG FIX (client freeze): this used to log every player-targeted entry unconditionally
		// whenever config.debugLogging() was on. PostMenuSort - unlike GameTick, which the rest of
		// this plugin's debug logging is safely scoped to - fires at up to render-frame frequency
		// for as long as the cursor rests over ANY interactive entity, not once per 600ms game
		// tick. Hovering a single player with a full menu (Attack/Follow/Trade with/Walk here/
		// Report/Lookup - 6 entries) could therefore produce hundreds of synchronous log calls per
		// second, which froze the client outright the first time someone left Debug logging on
		// while testing the teammate-freeze block (exactly the scenario that produces a menu like
		// that). Throttled to at most one burst per second, independent of how often this method
		// itself runs - this is a hard ceiling, not a best-effort one, so it can't regress the same
		// way even if this method's call frequency changes again in the future.
		boolean debug = config.debugLogging()
			&& System.currentTimeMillis() - lastMenuDebugLogMillis >= MENU_DEBUG_LOG_INTERVAL_MILLIS;
		if (debug)
		{
			lastMenuDebugLogMillis = System.currentTimeMillis();
		}

		List<MenuEntry> filtered = new ArrayList<>(menuEntries.length);
		int priorityIndex = -1;
		int anyEnemyAttackIndex = -1;
		boolean teammateRiskRemoved = false;

		for (MenuEntry entry : menuEntries)
		{
			Player player = entry.getPlayer();
			if (player == null)
			{
				filtered.add(entry);
				continue;
			}

			String stripped = stripMenuTags(entry.getOption());

			if (debug)
			{
				log.info("PvP Tools debug: menu entry option='{}' target='{}' type={} player='{}'",
					entry.getOption(), entry.getTarget(), entry.getType(), player.getName());
			}

			if ("Attack".equalsIgnoreCase(stripped))
			{
				if (highlights != null)
				{
					HighlightStyle style = highlights.get(player);
					if (style != null)
					{
						entry.setOption(ColorUtil.wrapWithColorTag("Attack", style.color));
					}
				}

				// Target attack-priority wins over a pile hit if a player happens to be both
				// (targetedPlayers only ever contains non-FC players who aren't the target anyway,
				// but this keeps the two conditions clearly independent regardless).
				if ((config.prioritiseTargetAttack() && targets.contains(player))
					|| (config.prioritiseAttackForPiles() && targetedPlayers.contains(player)))
				{
					priorityIndex = filtered.size();
				}
				// BUG FIX: an enemy who isn't specifically the caller's tracked target or a
				// detected pile (e.g. just another player stacked on the same tile as a teammate)
				// never set priorityIndex, so if that same menu also stripped out a teammate's
				// freeze-spell entry, the code below fell all the way through to promoting
				// "Cancel" - even though attacking that enemy is obviously a better default than
				// cancelling. Tracked separately from priorityIndex so an explicit target/pile
				// priority still always wins if both are present.
				else if (player != local && !isOwnTeamMember(player))
				{
					anyEnemyAttackIndex = filtered.size();
				}

				filtered.add(entry);
			}
			// CONFIRMED via debug logging: a manually-cast spell's WIDGET_TARGET_ON_PLAYER entry
			// always has option="Cast" - the spell's real name lives in the TARGET field instead,
			// formatted "<col=...>Snare</col><col=...> -> <col=...>PlayerName<col=...>  (level-92)".
			// So neither isFreezeSpellOption(stripped) (checks option) nor the selected-widget check
			// alone were ever going to match here - extractSpellNameFromTarget() below pulls the
			// text before " -> " out of the TARGET field instead, which is what's actually needed.
			// All three checks are kept together (any one matching is enough) since they're each
			// backed by real evidence for at least one entry shape, and keeping all three costs
			// nothing extra.
			else if (isFreezeSpellOption(stripped)
				|| (entry.getType() == MenuAction.WIDGET_TARGET_ON_PLAYER
					&& (isFreezeSpellOption(selectedSpellName) || isFreezeSpellOption(extractSpellNameFromTarget(entry.getTarget())))))
			{
				if (blockTeammateFreezes && player != local && isOwnTeamMember(player))
				{
					teammateRiskRemoved = true;
					continue; // dropped - never added to filtered
				}

				if (highlights != null)
				{
					HighlightStyle style = highlights.get(player);
					if (style != null)
					{
						// The visible spell name is the part of TARGET before " -> ", not OPTION
						// (which is just "Cast") - recolour that specifically, preserving the rest
						// of the target string (the arrow and player name/level) untouched. Falls
						// back to recolouring OPTION directly for the case where the option text
						// genuinely was the spell name (isFreezeSpellOption(stripped) matched).
						if (!recolorSpellTarget(entry, style.color))
						{
							entry.setOption(ColorUtil.wrapWithColorTag(entry.getOption(), style.color));
						}
					}
				}

				filtered.add(entry);
			}
			else
			{
				filtered.add(entry);
			}
		}

		// Recolouring alone (the common case - no reorder/removal needed) already applied live via
		// entry.setOption() above; only a structural change (reorder/removal) needs setMenuEntries().
		if (priorityIndex < 0 && !teammateRiskRemoved)
		{
			return;
		}

		MenuEntry[] result = filtered.toArray(new MenuEntry[0]);

		if (priorityIndex >= 0)
		{
			moveMenuEntryToEnd(result, priorityIndex);
		}
		else if (anyEnemyAttackIndex >= 0)
		{
			// No tracked target/pile here, but there IS some other enemy's Attack in this menu
			// (alongside the teammate entry that just got stripped) - that's still a far better
			// default than falling back to Cancel.
			moveMenuEntryToEnd(result, anyEnemyAttackIndex);
		}

		// BUG FIX: this used to unconditionally force "Cancel" (or "Walk here") to the default slot
		// whenever a teammate's freeze-spell entry was removed and neither branch above applied -
		// which is exactly what happens while a spell is selected and no "Attack" entry is even in
		// the menu (the normal case: you're trying to CAST, not attack). Removing only the
		// teammate's own entry already prevents it from ever being clicked - the game's own natural
		// ordering (which already puts the currently-selected spell's cast option at high priority)
		// is untouched by that removal, so an enemy's own Snare/etc entry that was already going to
		// be the default stays the default. Force-promoting Cancel on top of that was actively
		// breaking "snare an enemy while a teammate happens to also be nearby/stacked" - reported
		// directly: "I should be able to snare enemies through team members."

		client.setMenuEntries(result);
	}

	// PERFORMANCE: String.replaceAll(regex, ...) recompiles the regex from scratch on every single
	// call (it's just Pattern.compile(regex).matcher(this).replaceAll(...) under the hood) -
	// stripMenuTags() below can run several times per menu entry per onPostMenuSort pass while
	// actively hovering during a fight, so precompiling this once avoids repeatedly re-parsing the
	// same tiny regex for no benefit.
	private static final Pattern MENU_TAG_PATTERN = Pattern.compile("<[^>]*>");

	/**
	 * Strips any <col=...>/</col> (or other <...> tag) formatting the game may have already baked
	 * into a menu option/target string, so text-based matching isn't thrown off by it.
	 */
	private String stripMenuTags(String text)
	{
		return text == null ? "" : MENU_TAG_PATTERN.matcher(text).replaceAll("").trim();
	}

	private boolean isFreezeSpellOption(String strippedOption)
	{
		for (String spellName : FREEZE_SPELL_MENU_OPTIONS)
		{
			if (spellName.equalsIgnoreCase(strippedOption))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The action text of whichever spellbook button is currently "selected" (cursor showing the
	 * spell icon, about to be cast on the next click) - e.g. "Ice Barrage" - or null if nothing's
	 * selected, or what's selected isn't a spell.
	 *
	 * A manually-cast spell's WIDGET_TARGET_ON_PLAYER menu entry is a generic "a widget is
	 * selected, this click targets it at a player" action - the same action type covers using a
	 * selected inventory item on a player, not just spells - so its own entry.getOption() text
	 * isn't guaranteed to be the spell's plain name the way "Attack" reliably is; InteractHighlight
	 * (a RuneLite core plugin that also needs to know whether a spell specifically is selected)
	 * reads client.getSelectedWidget() for exactly this reason rather than trusting menu text.
	 * Cross-referencing the SPELLBOOK WIDGET's own action text - the same text the game itself
	 * would have used to build the menu entry in the first place - sidesteps whatever specifically
	 * made the option-text match unreliable here.
	 */
	private String getSelectedSpellbookActionName()
	{
		Widget selected = client.getSelectedWidget();
		if (selected == null)
		{
			return null;
		}

		if (WidgetUtil.componentToInterface(selected.getId()) != InterfaceID.MAGIC_SPELLBOOK)
		{
			return null;
		}

		String[] actions = selected.getActions();
		if (actions == null || actions.length == 0 || actions[0] == null)
		{
			return null;
		}

		return stripMenuTags(actions[0]);
	}

	/**
	 * Pulls the spell name out of a manually-cast spell's TARGET string - confirmed via debug
	 * logging to look like "<col=00ff00>Snare</col><col=ffffff> -> <col=ffffff>PlayerName<col=ff00>
	 * (level-92)" - i.e. everything before the literal " -> ", tags stripped. Returns null if the
	 * string doesn't contain that separator (not a spell-cast target).
	 */
	private String extractSpellNameFromTarget(String target)
	{
		if (target == null)
		{
			return null;
		}

		int arrowIndex = target.indexOf("->");
		if (arrowIndex < 0)
		{
			return null;
		}

		return stripMenuTags(target.substring(0, arrowIndex));
	}

	/**
	 * Recolours just the spell-name portion of a manually-cast spell's TARGET string (see
	 * extractSpellNameFromTarget()), leaving the " -> PlayerName (level-X)" portion exactly as the
	 * game rendered it. Returns false (does nothing) if the target isn't in that format, so the
	 * caller can fall back to recolouring OPTION instead.
	 */
	private boolean recolorSpellTarget(MenuEntry entry, Color color)
	{
		String target = entry.getTarget();
		if (target == null)
		{
			return false;
		}

		int arrowIndex = target.indexOf("->");
		if (arrowIndex < 0)
		{
			return false;
		}

		String spellName = stripMenuTags(target.substring(0, arrowIndex));
		String rest = target.substring(arrowIndex);
		entry.setTarget(ColorUtil.wrapWithColorTag(spellName, color) + " " + rest);
		return true;
	}

	/**
	 * Moves the entry at the given index to the end of the array (in place) - the default
	 * left-click action - shifting everything after it left by one to fill the gap.
	 */
	private void moveMenuEntryToEnd(MenuEntry[] menuEntries, int index)
	{
		int last = menuEntries.length - 1;
		if (index < 0 || index >= last)
		{
			return;
		}

		MenuEntry entry = menuEntries[index];
		System.arraycopy(menuEntries, index + 1, menuEntries, index, last - index);
		menuEntries[last] = entry;
	}

	/**
	 * Adds shift-right-click "Add caller" and "Add to snipe list" options to other players in the
	 * game world, so either list can be built without alt-tabbing to config to type a name. Only
	 * appears while shift is held (client.isKeyPressed(KeyCode.KC_SHIFT)) - same idiom used
	 * elsewhere in RuneLite for shift-only menu additions (e.g. NpcIndicatorsPlugin's tag option).
	 *
	 * Anchored on MenuOpened rather than MenuEntryAdded: MenuOpened fires once with the whole
	 * already-built menu, so every distinct player actor present (right-clicking a crowd can
	 * surface more than one, e.g. two players stacked on the same tile) can be deduped and
	 * handled in one pass, rather than needing to filter for one specific always-present option
	 * text (players have no single guaranteed option the way NPCs always have "Examine").
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		Player local = client.getLocalPlayer();
		MenuEntry[] entries = event.getMenuEntries();
		Set<Player> handled = new HashSet<>();

		for (int idx = entries.length - 1; idx >= 0; idx--)
		{
			MenuEntry entry = entries[idx];
			Actor actor = entry.getActor();
			if (!(actor instanceof Player) || actor == local)
			{
				continue;
			}

			Player player = (Player) actor;
			if (player.getName() == null || !handled.add(player))
			{
				continue;
			}

			client.createMenuEntry(idx)
				.setOption(ADD_CALLER)
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> addCaller(player.getName()));

			client.createMenuEntry(idx)
				.setOption(ADD_TO_SNIPE_LIST)
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> addToSnipeList(player.getName()));
		}
	}

	/**
	 * Appends a player to the "Callers" config list if they're not already on it, going through
	 * ConfigManager - the exact same path as manually editing the Callers config field - so
	 * onConfigChanged picks it up and rebuilds the sidebar panel automatically. Keeps a single
	 * source of truth for the caller list rather than a separate in-memory add mechanism.
	 */
	private void addCaller(String playerName)
	{
		if (playerName == null)
		{
			return;
		}

		if (!appendCallerIfNew(playerName, playerName))
		{
			return;
		}

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value("Added " + playerName + " as a caller.")
			.build());
	}

	/**
	 * Adds a caller typed directly into the sidebar panel's "Add caller" field. Supports the same
	 * "Name" or "Name:Nickname" syntax as the raw Callers config field, since the typed text is
	 * appended to that same text verbatim - duplicate-checking only looks at the name part, so
	 * typing a different nickname for an already-configured name is correctly rejected rather than
	 * added as a second entry. Package-private since PrepToolsPanel lives in this same package.
	 */
	void addCallerFromPanel(String rawInput)
	{
		if (rawInput == null || rawInput.trim().isEmpty())
		{
			return;
		}

		String trimmed = rawInput.trim();
		int colonIndex = trimmed.indexOf(':');
		String name = colonIndex >= 0 ? trimmed.substring(0, colonIndex).trim() : trimmed;
		if (name.isEmpty())
		{
			return;
		}

		appendCallerIfNew(name, trimmed);
	}

	/**
	 * Shared by addCaller() and addCallerFromPanel(): appends rawLineToAppend (already in the
	 * "Name" or "Name:Nickname" config text format) to the "Callers" config field if no existing
	 * entry's name already matches, standardized. Returns whether it was actually added.
	 */
	private boolean appendCallerIfNew(String name, String rawLineToAppend)
	{
		String standardizedName = Text.standardize(name);
		for (CallerEntry entry : parseCallerEntries(config.callers()))
		{
			if (Text.standardize(entry.name).equals(standardizedName))
			{
				return false;
			}
		}

		String existing = config.callers();
		String updated = (existing == null || existing.trim().isEmpty())
			? rawLineToAppend
			: existing.trim() + "\n" + rawLineToAppend;

		configManager.setConfiguration(PrepToolsConfig.GROUP, "callers", updated);
		return true;
	}

	/**
	 * Appends a player to the "Snipe list" config list if they're not already on it - mirrors
	 * addCaller()'s shift-right-click flow exactly, just against the snipe list instead.
	 */
	private void addToSnipeList(String playerName)
	{
		if (playerName == null)
		{
			return;
		}

		if (!appendSnipeNameIfNew(playerName))
		{
			return;
		}

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value("Added " + playerName + " to the snipe list.")
			.build());
	}

	/**
	 * Adds a name typed directly into the sidebar panel's snipe list "Add" field. Package-private
	 * since PrepToolsPanel lives in this same package.
	 */
	void addSnipeFromPanel(String rawInput)
	{
		if (rawInput == null || rawInput.trim().isEmpty())
		{
			return;
		}

		appendSnipeNameIfNew(rawInput.trim());
	}

	private boolean appendSnipeNameIfNew(String name)
	{
		String standardizedName = Text.standardize(name);
		for (String existingName : parseNameList(config.snipeList()))
		{
			if (Text.standardize(existingName).equals(standardizedName))
			{
				return false;
			}
		}

		String existing = config.snipeList();
		String updated = (existing == null || existing.trim().isEmpty())
			? name
			: existing.trim() + "\n" + name;

		configManager.setConfiguration(PrepToolsConfig.GROUP, "snipeList", updated);
		return true;
	}

	/**
	 * Called by the sidebar panel's delete button to remove a name from the "Snipe list" config
	 * list entirely. Package-private since PrepToolsPanel lives in this same package.
	 */
	void removeSnipedName(String name)
	{
		List<String> names = parseNameList(config.snipeList());
		String standardizedName = Text.standardize(name);

		boolean removed = names.removeIf(existingName -> Text.standardize(existingName).equals(standardizedName));
		if (!removed)
		{
			return;
		}

		configManager.setConfiguration(PrepToolsConfig.GROUP, "snipeList", String.join("\n", names));
	}

	/**
	 * Splits a comma/newline separated name list (the "Snipe list" config format - no nickname
	 * support, unlike parseCallerEntries()) into a plain list of trimmed, non-empty names.
	 */
	private List<String> parseNameList(String raw)
	{
		List<String> names = new ArrayList<>();
		if (raw == null || raw.trim().isEmpty())
		{
			return names;
		}

		for (String part : raw.split("[,\\n]"))
		{
			String trimmed = part.trim();
			if (!trimmed.isEmpty())
			{
				names.add(trimmed);
			}
		}

		return names;
	}

	/**
	 * Posts the name of the player you're currently following/interacting with into the chat
	 * input box, prefixed with the configured text. This PRE-FILLS the chat input only - it does
	 * not press Enter for you, so you still send the message yourself as a final, deliberate
	 * action (same as if you'd typed it by hand).
	 */
	private void postFollowedPlayerNameToChat()
	{
		// Everything that touches live game state (getLocalPlayer, getInteracting, etc.) must
		// run on the client thread - this hotkey fires on the AWT event thread, and reading
		// game state from there throws "must be called on client thread". Previously only the
		// final setVarcStrValue call was wrapped, which wasn't enough.
		clientThread.invoke(() ->
		{
			Player local = client.getLocalPlayer();
			if (local == null)
			{
				return;
			}

			Actor interacting = local.getInteracting();
			if (!(interacting instanceof Player))
			{
				return;
			}

			String targetName = interacting.getName();
			if (targetName == null)
			{
				return;
			}

			String prefix = config.chatSpamPrefix();
			String message = (prefix == null || prefix.trim().isEmpty())
				? targetName
				: prefix.trim() + " " + targetName;

			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, message);

			// Setting the var alone updates the underlying value (proven by Enter still working
			// even when nothing visibly changed) but doesn't tell the chat widget to redraw -
			// that redraw is normally only triggered by the client's own keyboard input handling.
			// This is the same script call real RuneLite plugins use after writing to
			// CHATBOX_TYPED_TEXT to force the visible text to actually refresh immediately.
			client.runScript(ScriptID.CHAT_PROMPT_INIT);
		});
	}

	/**
	 * Detects a player (any player, including the local player) being hit by a freeze-capable
	 * spell graphic, or by the teleblock spell's graphic, and starts/refreshes the matching
	 * countdown. Fires once per hit - the countdowns themselves are tracked separately and ticked
	 * down in updateFreezeTimers()/updateTeleblockTimers().
	 *
	 * Restructured from two independent early-return methods into one: both checks need the same
	 * "is this a player" guard, and a single GraphicChanged event only ever carries one graphic ID
	 * at a time, so a freeze hit and a teleblock hit can never both match for the same event -
	 * checking both here (rather than short-circuiting the whole method on !showFreezeTimers()) is
	 * what lets teleblock detection run independently of the freeze config toggle.
	 */
	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof Player))
		{
			return;
		}

		Player player = (Player) actor;

		if (config.showFreezeTimers())
		{
			FreezeSpellInfo info = FREEZE_SPELL_INFO.get(actor.getGraphic());
			if (info != null)
			{
				// "Hide for own team" is about decluttering teammates' freezes, not suppressing your
				// own - isOwnTeamMember() is true for the local player too (you're on your own
				// team), so that has to be excluded explicitly or your own freeze would never get
				// tracked while it's on.
				if (!(config.hideFreezeTimersForOwnTeam() && player != client.getLocalPlayer() && isOwnTeamMember(player)))
				{
					int startTick = client.getTickCount();
					// +1: testing showed the countdown hitting zero one full tick before the target
					// could actually move again. The wiki-quoted durations (e.g. Ice Barrage = 32
					// ticks) are correct, but there's an alignment gap of one tick between the
					// moment GraphicChanged fires and when the server-side unfreeze actually takes
					// effect, on top of the earlier movement-detection timing issue. This correction
					// targets that specific remaining gap.
					int endTick = startTick + info.durationTicks + 1;

					// If this player is already tracked as frozen with a later end tick, don't
					// shorten it - otherwise (new freeze, or existing one already expired) take the
					// new duration.
					FreezeState existing = freezeStates.get(player);
					if (existing == null || endTick > existing.endTick)
					{
						long durationMillis = (info.durationTicks + 1) * 600L;
						freezeStates.put(player, new FreezeState(info.spriteId, startTick, endTick, System.currentTimeMillis(), durationMillis));
						freezeLastKnownLocation.put(player, player.getWorldLocation());
					}
				}
			}
		}

		if (config.showTeleblockTimers() && actor.getGraphic() == SpotanimID.TELE_BLOCK_IMPACT)
		{
			if (!(config.hideTeleblockTimersForOwnTeam() && player != client.getLocalPlayer() && isOwnTeamMember(player)))
			{
				// Halved duration if the target was praying Protect from Magic at the moment the
				// spell landed - read here, at hit time, since the overhead icon can change again
				// immediately afterwards and wouldn't reflect what was actually active when it counted.
				boolean wasPrayingMagic = player.getOverheadIcon() == HeadIcon.MAGIC;
				int durationTicks = wasPrayingMagic ? TELEBLOCK_DURATION_TICKS_PROTECTED : TELEBLOCK_DURATION_TICKS;
				int startTick = client.getTickCount();
				int endTick = startTick + durationTicks;

				TeleblockState existing = teleblockStates.get(player);
				if (existing == null || endTick > existing.endTick)
				{
					long durationMillis = durationTicks * 600L;
					teleblockStates.put(player, new TeleblockState(startTick, endTick, System.currentTimeMillis(), durationMillis));
				}
			}
		}
	}

	/**
	 * Ticks down tracked freezes, removing any that have expired, and clears a freeze early if
	 * the player visibly moves - a genuinely frozen player cannot move at all, so movement means
	 * our assumed duration (or detection) was wrong, and showing a stale countdown would be
	 * actively misleading.
	 *
	 * BUG FIX: the movement check used to run from the very first tick a freeze was detected,
	 * which was clearing freezes ~3 ticks early. Root cause: OSRS queues movement a tick ahead -
	 * if a player clicked to walk right before getting frozen, that already-queued step can still
	 * play out for a tick or two AFTER the freeze has genuinely landed, even though the player is
	 * correctly frozen from that point on. Comparing position starting from tick zero read that
	 * trailing queued step as "they moved, so they can't be frozen" and cleared the timer early.
	 * Waiting MOVEMENT_CHECK_GRACE_TICKS before trusting position stability lets that queued step
	 * finish resolving first, so it's not mistaken for proof the freeze already ended.
	 */
	private void updateFreezeTimers()
	{
		// Also catches a team member who was already tracked before "Hide for own team" was
		// turned on, or who became a teammate mid-freeze (e.g. added as a caller after being hit).
		// Same local-player exclusion as onGraphicChanged() - see the comment there.
		if (config.hideFreezeTimersForOwnTeam())
		{
			Player local = client.getLocalPlayer();
			freezeStates.keySet().removeIf(player -> player != local && isOwnTeamMember(player));
		}

		int currentTick = client.getTickCount();

		// Transition expired freezes into their post-freeze immunity window instead of removing
		// them outright - same tracked entry, same icon, just a different phase.
		for (FreezeState state : freezeStates.values())
		{
			if (!state.immune && currentTick >= state.endTick)
			{
				state.immune = true;
				state.immunityEndTick = state.endTick + FREEZE_IMMUNITY_TICKS;
				state.immunityStartTimeMillis = System.currentTimeMillis();
			}
		}

		// Only remove once immunity has ALSO expired (non-immune entries have no immunity phase
		// end tick to check yet, so they're never removed here - only via the block above first
		// setting them immune, or the movement safety net below).
		freezeStates.entrySet().removeIf(entry ->
		{
			FreezeState state = entry.getValue();
			return state.immune && currentTick >= state.immunityEndTick;
		});
		freezeLastKnownLocation.keySet().removeIf(player -> !freezeStates.containsKey(player));

		List<Player> earlyClears = new ArrayList<>();

		for (Map.Entry<Player, FreezeState> entry : freezeStates.entrySet())
		{
			Player player = entry.getKey();
			FreezeState state = entry.getValue();

			// Once immune, the player is free to move around normally - movement is expected and
			// must not be used to clear anything early during this phase.
			if (state.immune)
			{
				continue;
			}

			WorldPoint currentLocation = player.getWorldLocation();

			if (currentTick - state.startTick < MOVEMENT_CHECK_GRACE_TICKS)
			{
				freezeLastKnownLocation.put(player, currentLocation);
				continue;
			}

			WorldPoint lastLocation = freezeLastKnownLocation.get(player);
			if (lastLocation != null && currentLocation != null && !currentLocation.equals(lastLocation))
			{
				earlyClears.add(player);
			}
			else
			{
				freezeLastKnownLocation.put(player, currentLocation);
			}
		}

		for (Player player : earlyClears)
		{
			freezeStates.remove(player);
			freezeLastKnownLocation.remove(player);
		}
	}

	/**
	 * Per-spell freeze duration and icon, keyed by spell graphic ID in FREEZE_SPELL_INFO.
	 */
	private static class FreezeSpellInfo
	{
		final int durationTicks;
		final int spriteId;

		FreezeSpellInfo(int durationTicks, int spriteId)
		{
			this.durationTicks = durationTicks;
			this.spriteId = spriteId;
		}
	}

	/**
	 * Tracked state for one player's active freeze. startTimeMillis/durationMillis are wall-clock
	 * based purely for smooth per-frame countdown display in FreezeOverlay; startTick/endTick
	 * (game-tick based) remain authoritative for actual expiry and the movement safety net, so
	 * display smoothing never affects correctness.
	 */
	static class FreezeState
	{
		final int spriteId;
		final int startTick;
		final int endTick;
		final long startTimeMillis;
		final long durationMillis;

		/**
		 * True once the freeze itself has expired and the player has moved into the post-freeze
		 * immunity window (during which they can't be frozen again). Same icon is shown, but the
		 * overlay renders the countdown in a different colour to distinguish the two phases.
		 */
		boolean immune;
		int immunityEndTick;
		long immunityStartTimeMillis;

		FreezeState(int spriteId, int startTick, int endTick, long startTimeMillis, long durationMillis)
		{
			this.spriteId = spriteId;
			this.startTick = startTick;
			this.endTick = endTick;
			this.startTimeMillis = startTimeMillis;
			this.durationMillis = durationMillis;
		}
	}

	/**
	 * Tracked state for one player's active teleblock - see onGraphicChanged()/TeleblockOverlay.
	 * No immunity phase and no movement-based early clearing (neither concept applies to teleblock -
	 * it doesn't prevent movement, and there's no post-expiry "can't be teleblocked again" window).
	 */
	static class TeleblockState
	{
		final int startTick;
		final int endTick;
		final long startTimeMillis;
		final long durationMillis;

		TeleblockState(int startTick, int endTick, long startTimeMillis, long durationMillis)
		{
			this.startTick = startTick;
			this.endTick = endTick;
			this.startTimeMillis = startTimeMillis;
			this.durationMillis = durationMillis;
		}
	}

	/**
	 * Ticks down tracked teleblocks, removing any that have expired. Also drops a team member who
	 * was already tracked before "Hide for own team" was turned on, or who became a teammate
	 * mid-teleblock - same idea as updateFreezeTimers()'s equivalent cleanup.
	 */
	private void updateTeleblockTimers()
	{
		if (config.hideTeleblockTimersForOwnTeam())
		{
			Player local = client.getLocalPlayer();
			teleblockStates.keySet().removeIf(player -> player != local && isOwnTeamMember(player));
		}

		int currentTick = client.getTickCount();
		teleblockStates.entrySet().removeIf(entry -> currentTick >= entry.getValue().endTick);
	}

	private void updateWeaponIndicators()
	{
		meleeAttackerCount = 0;
		rangedAttackerCount = 0;

		if (!config.showFightInfoOverlay() || !config.showAttackingYou())
		{
			previouslyBeingAttacked = false;
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			previouslyBeingAttacked = false;
			return;
		}

		boolean debug = config.debugLogging();
		int consideredPlayers = 0;

		for (Player player : client.getPlayers())
		{
			if (player == null || player == local)
			{
				continue;
			}

			if (player.getInteracting() != local)
			{
				continue;
			}

			consideredPlayers++;

			int weaponId = getEquippedWeaponId(player);

			if (debug)
			{
				log.info("PvP Tools debug: '{}' is interacting with you, equipped weapon item ID = {}",
					player.getName(), weaponId);
			}

			if (MELEE_WEAPON_IDS.contains(weaponId))
			{
				meleeAttackerCount++;
			}
			else if (RANGED_WEAPON_IDS.contains(weaponId))
			{
				rangedAttackerCount++;
			}
		}

		if (debug && consideredPlayers == 0)
		{
			log.info("PvP Tools debug: no players are currently interacting with you (nobody detected).");
		}

		boolean beingAttacked = consideredPlayers > 0;
		if (beingAttacked && !previouslyBeingAttacked)
		{
			attackerFlashEndTime = System.currentTimeMillis() + Math.max(0, config.attackerFlashDurationMillis());
		}
		previouslyBeingAttacked = beingAttacked;
	}

	/**
	 * Whether the "Attacking You" row is currently within its "someone new just engaged you" flash window.
	 */
	public boolean isFlashingAttacker()
	{
		return config.showAttackerFlash() && System.currentTimeMillis() < attackerFlashEndTime;
	}

	private int getEquippedWeaponId(Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return -1;
		}

		// getEquipmentId(KitType) already returns the plain item ID - no offset needed here.
		return composition.getEquipmentId(KitType.WEAPON);
	}

	/**
	 * Recomputes teamCapeCounts (team number -> nearby player count) and re-parses teamCapeNames
	 * from config, every tick - a plain full scan of client.getPlayers(), the same approach already
	 * used elsewhere in this plugin (updateOpponentInfo(), updateTargetedPlayers()) rather than
	 * tracking spawn/despawn/changed deltas the way vanilla RuneLite's Team plugin does; simpler,
	 * and cheap enough at normal player-count scale.
	 */
	private void updateTeamCapeCounts()
	{
		teamCapeNames = parseTeamCapeNames(config.teamCapeNames());

		if (!config.showTeamCapeOverlay())
		{
			teamCapeCounts = Collections.emptyMap();
			return;
		}

		Map<Integer, Integer> counts = new HashMap<>();
		for (Player player : client.getPlayers())
		{
			if (player != null && player.getTeam() > 0)
			{
				counts.merge(player.getTeam(), 1, Integer::sum);
			}
		}

		Map<Integer, Integer> sorted = new LinkedHashMap<>();
		counts.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
				.thenComparing(Map.Entry.comparingByKey()))
			.forEach(e -> sorted.put(e.getKey(), e.getValue()));

		teamCapeCounts = sorted;
	}

	/**
	 * Parses PrepToolsConfig#teamCapeNames ("TeamNumber:Name" per line/comma) into a lookup map.
	 * Malformed lines (no colon, or a non-numeric team number) are silently skipped.
	 */
	private Map<Integer, String> parseTeamCapeNames(String raw)
	{
		Map<Integer, String> names = new HashMap<>();
		if (raw == null || raw.trim().isEmpty())
		{
			return names;
		}

		for (String part : raw.split("[,\\n]"))
		{
			String trimmed = part.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}

			int colonIndex = trimmed.indexOf(':');
			if (colonIndex < 0)
			{
				continue;
			}

			String numberPart = trimmed.substring(0, colonIndex).trim();
			String namePart = trimmed.substring(colonIndex + 1).trim();
			if (namePart.isEmpty())
			{
				continue;
			}

			// Backstop for TEAM_CAPE_NAME_MAX_LENGTH - the sidebar panel's editor already prevents
			// typing past it, but this field can also be edited directly as raw text via RuneLite's
			// own config panel, which has no such restriction.
			if (namePart.length() > TEAM_CAPE_NAME_MAX_LENGTH)
			{
				namePart = namePart.substring(0, TEAM_CAPE_NAME_MAX_LENGTH);
			}

			try
			{
				names.put(Integer.parseInt(numberPart), namePart);
			}
			catch (NumberFormatException e)
			{
				// Skip malformed lines rather than failing the whole list over one typo.
			}
		}

		return names;
	}

	/**
	 * Renders a team-number -> name map back into the "Team cape names" config field's text
	 * format - the inverse of parseTeamCapeNames(). Used by setTeamCapeNameFromPanel()/
	 * removeTeamCapeName() to persist a change made from the sidebar panel's editor.
	 */
	private String serializeTeamCapeNames(Map<Integer, String> names)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, String> entry : names.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append(entry.getKey()).append(':').append(entry.getValue());
		}
		return sb.toString();
	}

	/**
	 * Called by the sidebar panel's Team Capes editor to add or replace the custom name for one
	 * team number. Package-private since PrepToolsPanel lives in this same package.
	 */
	void setTeamCapeNameFromPanel(int teamNumber, String rawName)
	{
		String name = rawName == null ? "" : rawName.trim();
		if (name.length() > TEAM_CAPE_NAME_MAX_LENGTH)
		{
			name = name.substring(0, TEAM_CAPE_NAME_MAX_LENGTH);
		}

		Map<Integer, String> names = parseTeamCapeNames(config.teamCapeNames());
		if (name.isEmpty())
		{
			names.remove(teamNumber);
		}
		else
		{
			names.put(teamNumber, name);
		}

		configManager.setConfiguration(PrepToolsConfig.GROUP, "teamCapeNames", serializeTeamCapeNames(names));
	}

	/**
	 * Called by the sidebar panel's delete button next to a configured team cape name.
	 */
	void removeTeamCapeName(int teamNumber)
	{
		Map<Integer, String> names = parseTeamCapeNames(config.teamCapeNames());
		if (names.remove(teamNumber) != null)
		{
			configManager.setConfiguration(PrepToolsConfig.GROUP, "teamCapeNames", serializeTeamCapeNames(names));
		}
	}

	/**
	 * Computes strengthBoosted/prayerBoosted for FightInfoOverlay's green/white Strength and
	 * Prayer colouring - both a straight comparison against the real (unboosted) level, no peak
	 * tracking. Strength additionally requires the boost to exceed PrepToolsConfig#
	 * strengthGreenThreshold() (default 0, i.e. any active boost at all) - e.g. with the threshold
	 * set to 10, a +11 boost (110/99) reads as boosted, and a +10 boost (109/99) doesn't.
	 */
	private void updatePlayerStatBoosts()
	{
		int currentStrength = client.getBoostedSkillLevel(Skill.STRENGTH);
		int realStrength = client.getRealSkillLevel(Skill.STRENGTH);
		strengthBoosted = (currentStrength - realStrength) > config.strengthGreenThreshold();

		int currentPrayer = client.getBoostedSkillLevel(Skill.PRAYER);
		int realPrayer = client.getRealSkillLevel(Skill.PRAYER);
		prayerBoosted = currentPrayer > realPrayer;
	}

	/**
	 * Refreshes lastKnownWorldPoint/currentWildernessLevel/currentLocationName from the local
	 * player's current position - see those fields' own docs.
	 */
	private void updateLocation()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		WorldPoint point = local.getWorldLocation();
		if (point == null)
		{
			return;
		}

		lastKnownWorldPoint = point;
		currentWildernessLevel = readWildernessLevelWidget();
		currentLocationName = findLocationName(point);

		// The "hovered tile" readout for the Location Mapper tool - getSelectedSceneTile() is the
		// same client-tracked "tile under the cursor" state the game itself uses for its own
		// Walk-here/tile-highlight behaviour, so no separate mouse-position-to-world-point maths is
		// needed here. Only updated once per tick (like everything else in this plugin) rather than
		// on every mouse-move/render frame - plenty responsive for a manual mapping tool.
		Tile hoveredTile = client.getSelectedSceneTile();
		hoveredWorldPoint = hoveredTile != null ? hoveredTile.getWorldLocation() : null;
		hoveredLocationName = hoveredWorldPoint != null ? findLocationName(hoveredWorldPoint) : null;
	}

	/**
	 * Reads the game's own wilderness level widget (InterfaceID.PvpIcons.WILDERNESSLEVEL, text
	 * like "Level: 15") rather than re-deriving a level from coordinates - this is exactly the
	 * approach RuneLite's own CombatLevelPlugin uses for the same widget, and it means this plugin
	 * never has to keep a hand-written formula in sync with wilderness geometry (which could easily
	 * shift in a future content revision this fork is tracking). Returns null while not in the
	 * wilderness (the widget doesn't exist at all then).
	 */
	private Integer readWildernessLevelWidget()
	{
		Widget widget = client.getWidget(InterfaceID.PvpIcons.WILDERNESSLEVEL);
		if (widget == null)
		{
			return null;
		}

		String text = widget.getText();
		if (text == null)
		{
			return null;
		}

		Matcher m = WILDERNESS_LEVEL_PATTERN.matcher(text);
		return m.matches() ? Integer.parseInt(m.group(1)) : null;
	}

	/**
	 * Resolves a WorldPoint to a user-named area, if it falls inside one of wildernessLocations -
	 * first match wins, so overlapping boxes fall back to whichever was added first.
	 */
	private String findLocationName(WorldPoint point)
	{
		for (WildernessLocation location : wildernessLocations)
		{
			if (location.contains(point.getX(), point.getY(), point.getPlane()))
			{
				return location.name;
			}
		}
		return null;
	}

	/**
	 * Loads wildernessLocations from config (see WILDERNESS_LOCATIONS_CONFIG_KEY's doc for why this
	 * is a raw config key rather than a visible ConfigItem), seeding a handful of default areas on
	 * first run.
	 */
	private void loadWildernessLocations()
	{
		String json = configManager.getConfiguration(PrepToolsConfig.GROUP, WILDERNESS_LOCATIONS_CONFIG_KEY);
		if (json == null || json.trim().isEmpty())
		{
			wildernessLocations = defaultWildernessLocations();
			saveWildernessLocations();
			return;
		}

		try
		{
			List<WildernessLocation> parsed = gson.fromJson(json, new TypeToken<List<WildernessLocation>>()
			{
			}.getType());
			wildernessLocations = parsed != null ? parsed : new ArrayList<>();
		}
		catch (RuntimeException e)
		{
			log.warn("PvP Tools: failed to parse saved wilderness locations, resetting to defaults", e);
			wildernessLocations = defaultWildernessLocations();
			saveWildernessLocations();
		}
	}

	private void saveWildernessLocations()
	{
		configManager.setConfiguration(PrepToolsConfig.GROUP, WILDERNESS_LOCATIONS_CONFIG_KEY, gson.toJson(wildernessLocations));
	}

	/**
	 * A handful of rough, eyeballed-not-surveyed starter boxes - just enough for the "Location
	 * Mapper" sidebar tool to have something to show/build on immediately, per CLAUDE.md's own
	 * note that this feature should "start with a very simple structure" if precise wilderness
	 * coordinate data is hard to come by. Walk to a real landmark, use Set Corner A/B + Save in the
	 * panel, and either replace one of these or add alongside it - that hands-on process is the
	 * actual intended way this list gets built out and verified, not these numbers.
	 */
	private List<WildernessLocation> defaultWildernessLocations()
	{
		List<WildernessLocation> defaults = new ArrayList<>();
		defaults.add(new WildernessLocation("Ferox Enclave", 3125, 3607, 3160, 3638, 0));
		defaults.add(new WildernessLocation("Wilderness Volcano", 3345, 3903, 3390, 3936, 0));
		defaults.add(new WildernessLocation("Lava Maze", 3050, 3810, 3070, 3830, 0));
		defaults.add(new WildernessLocation("Graveyard of Shadows", 3160, 3660, 3180, 3680, 0));
		defaults.add(new WildernessLocation("Mage Arena Bank", 3090, 3940, 3110, 3960, 0));
		return defaults;
	}

	/**
	 * Called by the sidebar panel's "Set Corner A/B" buttons - captures the local player's current
	 * position (cached in lastKnownWorldPoint every tick) as one corner of a new location box.
	 * Package-private since PrepToolsPanel lives in this same package.
	 */
	void setLocationCornerA()
	{
		locationCornerA = lastKnownWorldPoint;
		pushLocationMapperUpdate();
	}

	void setLocationCornerB()
	{
		locationCornerB = lastKnownWorldPoint;
		pushLocationMapperUpdate();
	}

	/**
	 * Pushes the full Location Mapper readout (current position, hovered tile, both corners) to
	 * the sidebar panel in one call - shared by onGameTick's per-tick refresh and the corner/save
	 * button handlers below, so the panel's method signature only needs updating in one place.
	 */
	private void pushLocationMapperUpdate()
	{
		panel.updateLocationMapper(lastKnownWorldPoint, currentWildernessLevel, currentLocationName,
			hoveredWorldPoint, hoveredLocationName, locationCornerA, locationCornerB);
	}

	/**
	 * Called by the sidebar panel's "Save Location" button once both corners are set and a name has
	 * been typed in. Normalizes the two corners into a min/max box regardless of which corner was
	 * set first/which direction they're offset from each other.
	 */
	void saveLocationFromPanel(String name)
	{
		if (name == null || name.trim().isEmpty() || locationCornerA == null || locationCornerB == null)
		{
			return;
		}

		if (locationCornerA.getPlane() != locationCornerB.getPlane())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.value("PvP Tools: both corners must be on the same plane/floor.")
				.build());
			return;
		}

		int minX = Math.min(locationCornerA.getX(), locationCornerB.getX());
		int maxX = Math.max(locationCornerA.getX(), locationCornerB.getX());
		int minY = Math.min(locationCornerA.getY(), locationCornerB.getY());
		int maxY = Math.max(locationCornerA.getY(), locationCornerB.getY());

		wildernessLocations.add(new WildernessLocation(name.trim(), minX, minY, maxX, maxY, locationCornerA.getPlane()));
		saveWildernessLocations();

		locationCornerA = null;
		locationCornerB = null;
		panel.updateWildernessLocations(wildernessLocations);
		pushLocationMapperUpdate();
	}

	/**
	 * Called by the sidebar panel's delete button next to a saved location.
	 */
	void removeWildernessLocation(WildernessLocation location)
	{
		if (wildernessLocations.remove(location))
		{
			saveWildernessLocations();
			panel.updateWildernessLocations(wildernessLocations);
		}
	}

	/**
	 * Types the current wilderness location (name and/or level, whatever's known) into the chat
	 * input box - prefill only, same "you still press Enter" model as postFollowedPlayerNameToChat().
	 */
	private void postLocationToChat()
	{
		clientThread.invoke(() ->
		{
			String name = currentLocationName;
			Integer level = currentWildernessLevel;
			if (name == null && level == null)
			{
				return;
			}

			StringBuilder sb = new StringBuilder();
			String prefix = config.locationSpamPrefix();
			if (prefix != null && !prefix.trim().isEmpty())
			{
				sb.append(prefix.trim()).append(' ');
			}
			sb.append(name != null ? name : "Wilderness");
			if (level != null)
			{
				sb.append(" (level ").append(level).append(')');
			}

			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, sb.toString());
			client.runScript(ScriptID.CHAT_PROMPT_INIT);
		});
	}

	/**
	 * Appends one line to the Friends Chat Log (newest-first, capped at
	 * PrepToolsConfig#friendsChatLogMaxEntries) and pushes the update to the sidebar panel.
	 */
	private void logFriendsChatActivity(String playerName, String activity)
	{
		if (!config.enableFriendsChatLog() || playerName == null)
		{
			return;
		}

		friendsChatLog.addFirst(new FriendsChatLogEntry(playerName, activity));

		int max = Math.max(1, config.friendsChatLogMaxEntries());
		while (friendsChatLog.size() > max)
		{
			friendsChatLog.removeLast();
		}

		panel.updateFriendsChatLog(friendsChatLog);
	}

	/**
	 * Logs a friends chat member joining. Package-private visibility not needed - RuneLite's
	 * eventbus finds @Subscribe methods reflectively regardless of access modifier - but public to
	 * match this class's other @Subscribe handlers.
	 */
	@Subscribe
	public void onFriendsChatMemberJoined(FriendsChatMemberJoined event)
	{
		FriendsChatMember member = event.getMember();
		if (member != null)
		{
			logFriendsChatActivity(member.getName(), "joined the friends chat");
		}
	}

	/**
	 * Logs a friends chat member leaving. NOTE: this also fires when a member simply logs out
	 * (they're removed from the friends chat member list either way) - there's no public API signal
	 * that distinguishes "left the chat while staying logged in" from "logged out", so both are
	 * logged identically here rather than guessing.
	 */
	@Subscribe
	public void onFriendsChatMemberLeft(FriendsChatMemberLeft event)
	{
		FriendsChatMember member = event.getMember();
		if (member != null)
		{
			logFriendsChatActivity(member.getName(), "left the friends chat");
		}
	}

	/**
	 * Logs a friends chat member's death, detected via their death animation - only works while
	 * they're actually on screen (an animation change for an off-screen/despawned player can't be
	 * observed at all). Restricted to PLAYER_DEATH_ANIMATION_IDS (the common human death anims) -
	 * see that constant's own doc for the coverage caveat.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (!config.enableFriendsChatLog())
		{
			return;
		}

		Actor actor = event.getActor();
		if (!(actor instanceof Player) || !PLAYER_DEATH_ANIMATION_IDS.contains(actor.getAnimation()))
		{
			return;
		}

		Player player = (Player) actor;
		if (player.getName() == null || !getFriendsChatMemberNames().contains(Text.standardize(player.getName())))
		{
			return;
		}

		logFriendsChatActivity(player.getName(), "died");
	}

	/**
	 * Splits the "Callers" config text into an ordered list of entries, parsing the optional
	 * ':Nickname' suffix on each line.
	 *
	 * Accepted formats per entry (comma or newline separated):
	 *   Name              - plain caller, no nickname
	 *   Name:Nickname     - display "Nickname" instead of the real name
	 *
	 * Note: which caller is PRIMARY is no longer set via this text field at all - it's chosen
	 * from the sidebar panel (PrepToolsPanel).
	 */
	private List<CallerEntry> parseCallerEntries(String raw)
	{
		List<CallerEntry> entries = new ArrayList<>();
		if (raw == null || raw.trim().isEmpty())
		{
			return entries;
		}

		for (String part : raw.split("[,\\n]"))
		{
			String trimmed = part.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}

			String name;
			String nickname = null;

			int colonIndex = trimmed.indexOf(':');
			if (colonIndex >= 0)
			{
				name = trimmed.substring(0, colonIndex).trim();
				String rawNickname = trimmed.substring(colonIndex + 1).trim();
				if (!rawNickname.isEmpty())
				{
					nickname = rawNickname;
				}
			}
			else
			{
				name = trimmed;
			}

			if (!name.isEmpty())
			{
				entries.add(new CallerEntry(name, nickname));
			}
		}

		return entries;
	}

	/**
	 * One parsed line from the "Callers" config field.
	 */
	static class CallerEntry
	{
		final String name;
		final String nickname;

		CallerEntry(String name, String nickname)
		{
			this.name = name;
			this.nickname = nickname;
		}
	}
}
