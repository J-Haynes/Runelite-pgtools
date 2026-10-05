package net.runelite.client.plugins.preptools;

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Nameable;
import net.runelite.api.Friend;
import net.runelite.api.Ignore;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.FriendsChatMemberJoined;
import net.runelite.api.events.FriendsChatMemberLeft;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.NameableNameChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.Text;

/**
 * PvP Tools: the wiring. Each feature lives in its own class - this routes game events to them,
 * reloads them when config changes, and keeps the sidebar (PrepToolsPanel) in sync:
 * <ul>
 * <li>TeamTracker - who's on your team (refreshed first every tick; everything else reads it)</li>
 * <li>HighlightTracker - callers, target, snipes, piles, attackers and their highlight styles</li>
 * <li>MenuHandler - right-click menu changes</li>
 * <li>FreezeTracker - freeze and teleblock timers</li>
 * <li>FightInfoTracker - the infobox's numbers</li>
 * <li>AttackAlert - attacked/frozen sound and screen border</li>
 * <li>LocationTracker - position, wilderness level/location, the location list</li>
 * <li>ChatHotkeys and ScoutReporter - the hotkeys</li>
 * <li>ConfigListEditor - edits to the caller/snipe/spam/cape lists</li>
 * <li>Experimental: FoodTracker (enemy food eaten), AttackRangeFilter (combat bracket),
 * TeamPerformanceTracker (the Stats tab), PlayerNicknameTracker (the Names tab)</li>
 * </ul>
 * Game state is only touched on the client thread; the sidebar only on the Swing EDT - see
 * onSwing().
 */
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
	private static final String SET_NICKNAME = "Set nickname";
	private static final String RESET_FOOD_COUNT = "Reset food count";

	private static final String FRIEND_NOTES_GROUP = ConfigListEditor.FRIEND_NOTES_GROUP;
	private static final String FRIEND_NOTES_KEY_PREFIX = ConfigListEditor.FRIEND_NOTES_KEY_PREFIX;
	private static final int FRIEND_NOTE_NICKNAME_MAX_LENGTH = 20;
	private static final int PERFORMANCE_PUSH_INTERVAL_TICKS = 5;

	/**
	 * Human death animations, for the FC log. Not exhaustive, but covers almost every PvP death.
	 */
	private static final Set<Integer> PLAYER_DEATH_ANIMATION_IDS = Set.of(AnimationID.HUMAN_DEATH, AnimationID.HUMAN_DEATH_BACKWARDS);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private PrepToolsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChatMessageManager chatMessageManager;

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
	private MultiLinesOverlay multiLinesOverlay;

	@Inject
	private ScreenBorderFlashOverlay screenBorderFlashOverlay;

	@Inject
	private FoodOverlay foodOverlay;

	@Inject
	private PlayerNicknameOverlay playerNicknameOverlay;

	@Inject
	private NicknameTooltipOverlay nicknameTooltipOverlay;

	@Inject
	private OthersOverlay othersOverlay;

	@Inject
	private WorldNumberOverlay worldNumberOverlay;

	@Inject
	private TeamTracker team;

	@Inject
	private HighlightTracker highlights;

	@Inject
	private MenuHandler menu;

	@Inject
	private FreezeTracker freezes;

	@Inject
	private FightInfoTracker fightInfo;

	@Inject
	private AttackAlert attackAlert;

	@Inject
	private LocationTracker locations;

	@Inject
	private ChatHotkeys hotkeys;

	@Inject
	private ConfigListEditor lists;

	@Inject
	private FoodTracker food;

	@Inject
	private AttackRangeFilter range;

	@Inject
	private TeamPerformanceTracker performance;

	@Inject
	private PlayerNicknameTracker nicknames;

	@Inject
	private FriendRenameTracker renames;

	private PrepToolsPanel panel;

	private final HotkeyListener foodResetListener = new HotkeyListener(() -> config.foodResetHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invoke(() ->
			{
				if (config.showFoodCounter())
				{
					food.clear();
					chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.CONSOLE)
						.value("PvP Tools: food counts reset.")
						.build());
				}
			});
		}
	};
	private NavigationButton navButton;

	/**
	 * Team cape number to how many players on screen wear it, most first - for TeamCapeOverlay.
	 */
	@Getter
	private Map<Integer, Integer> teamCapeCounts = Collections.emptyMap();

	/**
	 * The Capes tab's team cape names, reloaded when that config changes.
	 */
	@Getter
	private volatile Map<Integer, String> teamCapeNames = Collections.emptyMap();

	/**
	 * Newest first, capped at friendsChatLogMaxEntries. Session only. Client thread only.
	 */
	private final Deque<FriendsChatLogEntry> friendsChatLog = new ArrayDeque<>();

	private boolean wasInInstance;

	private MemberCountInfoBox friendsChatCountInfoBox;
	private MemberCountInfoBox clanCountInfoBox;

	/**
	 * The feature classes share state (everything reads TeamTracker, the overlays read the
	 * trackers), so each must be one instance - bound as singletons in this plugin's own child
	 * injector, which lives exactly as long as the plugin.
	 */
	@Override
	public void configure(Binder binder)
	{
		for (Class<?> feature : new Class<?>[]{TeamTracker.class, HighlightTracker.class, MenuHandler.class,
			FreezeTracker.class, FightInfoTracker.class, AttackAlert.class, LocationTracker.class, ChatHotkeys.class,
			ScoutReporter.class, ConfigListEditor.class, FoodTracker.class, AttackRangeFilter.class,
			TeamPerformanceTracker.class, PlayerNicknameTracker.class, FriendRenameTracker.class})
		{
			binder.bind(feature).in(Singleton.class);
		}
	}

	@Provides
	PrepToolsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PrepToolsConfig.class);
	}

	@Override
	protected void startUp()
	{
		clearState();
		migrateAttackPriorityOrder();
		locations.load();
		reloadConfigLists();

		overlayManager.add(overlay);
		overlayManager.add(nameOverlay);
		overlayManager.add(minimapOverlay);
		overlayManager.add(fightInfoOverlay);
		overlayManager.add(freezeOverlay);
		overlayManager.add(teleblockOverlay);
		overlayManager.add(teamCapeOverlay);
		overlayManager.add(wildernessLocationDebugOverlay);
		overlayManager.add(screenBorderFlashOverlay);
		overlayManager.add(multiLinesOverlay);
		overlayManager.add(foodOverlay);
		overlayManager.add(playerNicknameOverlay);
		overlayManager.add(nicknameTooltipOverlay);
		overlayManager.add(othersOverlay);
		overlayManager.add(worldNumberOverlay);
		multiLinesOverlay.load();
		hotkeys.start();
		keyManager.registerKeyListener(foodResetListener);

		panel = new PrepToolsPanel(this, lists, spriteManager, itemManager);
		navButton = NavigationButton.builder()
			.tooltip("PvP Tools")
			.icon(createNavIcon())
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		rebuildPanelLists();
		pushWildernessLocations();
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
		overlayManager.remove(screenBorderFlashOverlay);
		overlayManager.remove(multiLinesOverlay);
		overlayManager.remove(foodOverlay);
		overlayManager.remove(playerNicknameOverlay);
		overlayManager.remove(nicknameTooltipOverlay);
		overlayManager.remove(othersOverlay);
		overlayManager.remove(worldNumberOverlay);
		multiLinesOverlay.clear();
		hotkeys.stop();
		keyManager.unregisterKeyListener(foodResetListener);
		removeMemberCountInfoBoxes();
		clientToolbar.removeNavigation(navButton);
		clearState();
	}

	private void clearState()
	{
		team.clear();
		highlights.clear();
		freezes.clear();
		fightInfo.clear();
		attackAlert.clear();
		locations.clear();
		food.clear();
		range.clear();
		performance.reset();
		nicknames.clear();
		teamCapeCounts = Collections.emptyMap();
		friendsChatLog.clear();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Through the Clan Wars portal (into an instance): a new round - full food for everyone,
		// fresh team stats.
		WorldView worldView = client.getTopLevelWorldView();
		boolean inInstance = worldView != null && worldView.isInstance();
		if (inInstance && !wasInInstance)
		{
			food.startRound();
			performance.startRound();
			pushPerformance();
		}
		wasInInstance = inInstance;

		// Team first - every other feature reads it.
		team.refresh();
		nicknames.onGameTick();
		// Freezes and attack range before highlights - both change a target/pile's colour.
		freezes.onGameTick();
		range.onGameTick();
		highlights.onGameTick();
		performance.onGameTick(highlights.getPrimaryCaller(), highlights.getTargets());
		food.onGameTick();
		fightInfo.onGameTick();
		attackAlert.onGameTick();
		locations.onGameTick();
		updateTeamCapeCounts();
		updateMemberCountInfoBoxes();
		pushTickToPanel();
		if (renames.onGameTick())
		{
			pushPendingRenames();
		}
		if (client.getTickCount() % PERFORMANCE_PUSH_INTERVAL_TICKS == 0)
		{
			pushPerformance();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (FRIEND_NOTES_GROUP.equals(event.getGroup()))
		{
			loadPlayerNicknames();
			return;
		}
		if (!PrepToolsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		switch (event.getKey())
		{
			case "callers":
			case "snipeList":
			case "teamCapeNames":
			case "spamMessages":
			case "selectedSpamMessage":
			case "spamRandom":
			case "playerNicknames":
			case "useFriendNotesAsNicknames":
				reloadConfigLists();
				rebuildPanelLists();
				break;
			case "enablePerformanceTracker":
				pushPerformance();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			// Multi lines are clipped to the loaded scene; login, hop and region changes reload it.
			multiLinesOverlay.rebuildSceneLines();
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			// Everyone tracked belonged to the old world/session - don't carry them over.
			removeMemberCountInfoBoxes();
			team.clear();
			highlights.clear();
			freezes.clear();
			fightInfo.clear();
			attackAlert.clear();
			range.clear();
			performance.clearPlayers();
			food.clearPlayers();
			nicknames.clear();
			renames.onSessionChanged();
			teamCapeCounts = Collections.emptyMap();
		}
	}

	/**
	 * "Set nickname" on a player's name in the friends/ignore list, friends chat, clan list, chatbox
	 * and private chat - one entry per name, keyed on an option each of those lists always has
	 * (the same choice core's Hiscore plugin makes for its Lookup option).
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.showPlayerNicknames()
			|| (event.getType() != MenuAction.CC_OP.getId() && event.getType() != MenuAction.CC_OP_LOW_PRIORITY.getId()))
		{
			return;
		}

		String option = event.getOption();
		int componentId = event.getActionParam1();
		int groupId = WidgetUtil.componentToInterface(componentId);
		boolean nameEntry = groupId == InterfaceID.FRIENDS && option.equals("Delete")
			|| groupId == InterfaceID.IGNORE && option.equals("Delete")
			|| groupId == InterfaceID.CHATCHANNEL_CURRENT && (option.equals("Add ignore") || option.equals("Remove friend"))
			|| (componentId == InterfaceID.ClansSidepanel.PLAYERLIST || componentId == InterfaceID.ClansGuestSidepanel.PLAYERLIST)
				&& (option.equals("Add ignore") || option.equals("Remove friend"))
			|| (groupId == InterfaceID.CHATBOX || groupId == InterfaceID.PM_CHAT) && (option.equals("Add ignore") || option.equals("Message"));
		if (!nameEntry)
		{
			return;
		}

		String name = Text.toJagexName(Text.removeTags(event.getTarget()));
		if (name.isEmpty())
		{
			return;
		}
		client.createMenuEntry(-2)
			.setOption(SET_NICKNAME)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> promptNickname(name));
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		menu.onClientTick();
	}

	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		menu.onPostMenuSort();
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (event.getActor() instanceof Player && freezes.onGraphicChanged((Player) event.getActor()))
		{
			attackAlert.onLocalFrozen();
		}
	}

	/**
	 * Shift-right-click a player: "Add caller" / "Add to snipe list" (only if they aren't already).
	 * Shift-right-click the ground while mapping (showWildernessLocationOverlay): "Set Corner A/B"
	 * on the clicked tile. MenuOpened sees the whole menu at once, so stacked players are each
	 * handled once.
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
			Player player = entry.getPlayer();
			if (player == null || player == local || player.getName() == null || !handled.add(player))
			{
				continue;
			}

			String name = player.getName();
			if (!lists.isCaller(name))
			{
				client.createMenuEntry(idx)
					.setOption(ADD_CALLER)
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> lists.addCaller(name));
			}
			if (!lists.isSniped(name))
			{
				client.createMenuEntry(idx)
					.setOption(ADD_TO_SNIPE_LIST)
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> lists.addToSnipeList(name));
			}
			if (config.showFoodCounter() && food.hasCount(player))
			{
				client.createMenuEntry(idx)
					.setOption(RESET_FOOD_COUNT)
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> food.reset(player));
			}
			if (config.showPlayerNicknames())
			{
				client.createMenuEntry(idx)
					.setOption(SET_NICKNAME)
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> promptNickname(name));
			}
		}

		if (!config.showWildernessLocationOverlay())
		{
			return;
		}

		// The clicked tile is the "last right-clicked tile", as GroundMarkerPlugin reads it. NOT the
		// WALK entry's param0/param1 - those aren't scene coordinates, and landed hundreds of tiles off.
		Tile selectedTile = client.getTopLevelWorldView().getSelectedSceneTile();
		WorldPoint clicked = selectedTile != null ? selectedTile.getWorldLocation() : null;
		for (int idx = entries.length - 1; idx >= 0 && clicked != null; idx--)
		{
			if (entries[idx].getType() != MenuAction.WALK)
			{
				continue;
			}

			client.createMenuEntry(idx)
				.setOption("Set Corner A")
				.setTarget("")
				.setType(MenuAction.RUNELITE)
				.onClick(e -> setLocationCornerA(clicked));
			client.createMenuEntry(idx)
				.setOption("Set Corner B")
				.setTarget("")
				.setType(MenuAction.RUNELITE)
				.onClick(e -> setLocationCornerB(clicked));
			break;
		}
	}

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
	 * Also fires when a member logs out - the API doesn't say which, so both read "left".
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
	 * Eats (FoodTracker), attacks (TeamPerformanceTracker), and deaths: a death ends freezes/TB
	 * and FoodTracker's hp reading, and logs an FC member's death (only visible on screen). Your own
	 * death is logged from onActorDeath instead.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof Player))
		{
			return;
		}

		Player player = (Player) actor;
		food.onAnimationChanged(player);
		performance.onAnimationChanged(player);
		if (!PLAYER_DEATH_ANIMATION_IDS.contains(actor.getAnimation()))
		{
			return;
		}

		freezes.forget(player);
		// In Clan Wars a dead player is out; next round they're back with full food.
		food.reset(player);
		performance.onDeath(player);
		String standard = team.standardName(player);
		if (player != client.getLocalPlayer() && standard != null && team.getFriendsChatNames().contains(standard))
		{
			logFriendsChatActivity(player.getName(), "died");
		}
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		freezes.forget(event.getPlayer());
		performance.forget(event.getPlayer());
		food.forget(event.getPlayer());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		performance.onHitsplatApplied(event.getActor(), event.getHitsplat());
		food.onHitsplatApplied(event.getActor(), event.getHitsplat());
	}

	/**
	 * A friends/ignore list player changed their name - the only name change the game tells us
	 * about. Their nickname moves to the new name.
	 */
	@Subscribe
	public void onNameableNameChanged(NameableNameChanged event)
	{
		Nameable nameable = event.getNameable();
		if ((nameable instanceof Friend || nameable instanceof Ignore) && nameable.getPrevName() != null)
		{
			lists.renamePlayerNickname(Text.toJagexName(nameable.getPrevName()), Text.toJagexName(nameable.getName()));
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() instanceof Player)
		{
			Player player = (Player) event.getActor();
			freezes.forget(player);
			food.reset(player);
			performance.onDeath(player);
		}
		if (event.getActor() == client.getLocalPlayer())
		{
			logFriendsChatActivity("You", "died");
		}
	}

	/**
	 * Chatbox prompt for a player's nickname, pre-filled with the current one - empty removes it.
	 */
	private void promptNickname(String name)
	{
		String current = lists.getPlayerNickname(name);
		chatboxPanelManager.openTextInput("Nickname for " + Text.removeTags(name) + " (empty to remove)")
			.value(current != null ? current : "")
			.onDone((String nickname) -> lists.setPlayerNickname(name, nickname))
			.build();
	}

	// ============================================================================================
	// Called by the sidebar (EDT) for the Location tab.
	// ============================================================================================

	void setLocationCornerA()
	{
		setLocationCornerA(locations.getPosition());
	}

	void setLocationCornerB()
	{
		setLocationCornerB(locations.getPosition());
	}

	void setLocationCornerA(WorldPoint point)
	{
		locations.setCornerA(point);
		pushLocationMapper();
	}

	void setLocationCornerB(WorldPoint point)
	{
		locations.setCornerB(point);
		pushLocationMapper();
	}

	void updateLocationPreview(String name, int minX, int minY, int maxX, int maxY, int plane)
	{
		locations.setPreview(new WildernessLocation(name, minX, minY, maxX, maxY, plane));
	}

	void clearLocationPreview()
	{
		locations.setPreview(null);
	}

	void saveLocationFromPanel(String name, int priority)
	{
		WorldPoint a = locations.getCornerA();
		WorldPoint b = locations.getCornerB();
		if (a != null && b != null && a.getPlane() != b.getPlane())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.value("PvP Tools: both corners must be on the same plane/floor.")
				.build());
			return;
		}
		if (locations.saveCorners(name, priority))
		{
			pushWildernessLocations();
			pushLocationMapper();
		}
	}

	/**
	 * Hides a shipped location (restorable) or deletes one of the user's own.
	 */
	void removeWildernessLocation(String id)
	{
		locations.getStore().remove(id);
		pushWildernessLocations();
	}

	/**
	 * The corners are any two opposite corners, not necessarily min/max.
	 */
	void updateWildernessLocation(String id, String name, int corner1X, int corner1Y, int corner2X, int corner2Y, int plane, int priority)
	{
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		locations.getStore().update(id, name.trim(), Math.min(corner1X, corner2X), Math.min(corner1Y, corner2Y),
			Math.max(corner1X, corner2X), Math.max(corner1Y, corner2Y), plane, priority);
		pushWildernessLocations();
	}

	void revertWildernessLocation(String id)
	{
		locations.getStore().revert(id);
		pushWildernessLocations();
	}

	void restoreHiddenWildernessLocations()
	{
		locations.getStore().restoreHidden();
		pushWildernessLocations();
	}

	/**
	 * The full merged list in the shipped file's format - a drop-in wilderness_locations.json.
	 */
	String exportWildernessLocations()
	{
		return locations.getStore().export();
	}

	File saveWildernessLocationsExport(String json)
	{
		return locations.saveExport(json);
	}

	// ============================================================================================
	// Config lists and the sidebar
	// ============================================================================================

	/**
	 * Parses the list-valued config once per change, rather than every tick.
	 */
	private void reloadConfigLists()
	{
		List<CallerEntry> callers = CallerEntry.parseAll(config.callers());
		Set<String> callerNames = new HashSet<>();
		for (CallerEntry entry : callers)
		{
			callerNames.add(Text.standardize(entry.name));
		}
		team.setConfiguredCallers(callerNames);
		highlights.setCallers(callers);
		highlights.setSnipeNames(ConfigLists.parseNameList(config.snipeList()));
		teamCapeNames = ConfigLists.parseTeamCapeNames(config.teamCapeNames());
		loadPlayerNicknames();
	}

	/**
	 * The Names tab's nicknames, over Friend Notes' notes (if used) for anyone without one.
	 */
	private void loadPlayerNicknames()
	{
		Map<String, String> merged = new LinkedHashMap<>();
		if (config.useFriendNotesAsNicknames())
		{
			String prefix = FRIEND_NOTES_GROUP + "." + FRIEND_NOTES_KEY_PREFIX;
			for (String key : configManager.getConfigurationKeys(prefix))
			{
				String name = key.substring(prefix.length());
				String note = configManager.getConfiguration(FRIEND_NOTES_GROUP, FRIEND_NOTES_KEY_PREFIX + name);
				String nickname = friendNoteNickname(note);
				if (nickname != null)
				{
					merged.put(name, nickname);
				}
			}
		}
		// Put after the notes, so the Names tab wins for the same (standardized) name.
		merged.putAll(ConfigLists.parsePlayerNicknames(config.playerNicknames()));
		nicknames.setNicknames(merged);
	}

	/**
	 * A note's first line, cut short - a note can be a paragraph, a nickname tag can't.
	 */
	static String friendNoteNickname(String note)
	{
		if (note == null)
		{
			return null;
		}
		String line = note.split("\\R", 2)[0].trim();
		if (line.isEmpty())
		{
			return null;
		}
		return line.length() > FRIEND_NOTE_NICKNAME_MAX_LENGTH ? line.substring(0, FRIEND_NOTE_NICKNAME_MAX_LENGTH).trim() + ".." : line;
	}

	/**
	 * Swing isn't thread-safe: the sidebar is only ever touched on the EDT. Game data is
	 * snapshotted on the client thread first and handed over.
	 */
	private static void onSwing(Runnable update)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			update.run();
		}
		else
		{
			SwingUtilities.invokeLater(update);
		}
	}

	private void rebuildPanelLists()
	{
		List<CallerEntry> callers = CallerEntry.parseAll(config.callers());
		List<String> snipes = ConfigLists.parseNameList(config.snipeList());
		Map<Integer, String> capeNames = teamCapeNames;
		List<String> spam = ConfigLists.parseSpamMessages(config.spamMessages());
		String selectedSpam = config.selectedSpamMessage();
		boolean spamRandom = config.spamRandom();
		Map<String, String> playerNicknames = ConfigLists.parsePlayerNicknames(config.playerNicknames());
		onSwing(() ->
		{
			panel.rebuildPlayerNicknames(playerNicknames);
			panel.rebuild(callers);
			panel.rebuildSnipeList(snipes);
			panel.rebuildTeamCapeNames(capeNames);
			panel.rebuildSpamMessages(spam, selectedSpam, spamRandom);
		});
	}

	private void pushTickToPanel()
	{
		Set<String> onScreenCallers = new HashSet<>();
		for (Player caller : highlights.getBackupCallers())
		{
			onScreenCallers.add(team.standardName(caller));
		}
		Player primary = highlights.getPrimaryCaller();
		String primaryName = team.standardName(primary);
		if (primaryName != null)
		{
			onScreenCallers.add(primaryName);
		}

		Set<String> onScreenSnipes = new HashSet<>();
		for (Player sniped : highlights.getSnipedPlayers())
		{
			onScreenSnipes.add(team.standardName(sniped));
		}

		List<String> missing = computeMissingMembers();
		List<ClanIdRow> clanIds = computeClanIdRows(onScreenCallers);
		ClanWeaponSummary clanWeapons = computeClanWeapons();
		onSwing(() ->
		{
			panel.updateStatus(onScreenCallers, primaryName);
			panel.updateSnipeStatus(onScreenSnipes);
			panel.updateMissingMembers(missing);
			panel.updateClanIds(clanIds);
			panel.updateClanWeapons(clanWeapons);
		});
		pushLocationMapper();
	}

	private void pushPendingRenames()
	{
		List<FriendRenameTracker.PendingRename> pending = renames.getPending();
		onSwing(() -> panel.updatePendingRenames(pending));
	}

	/**
	 * The Names tab paired a possibly-renamed player with a new name (EDT).
	 */
	void resolvePendingRename(String oldName, String newName)
	{
		clientThread.invoke(() ->
		{
			renames.resolve(oldName, newName);
			pushPendingRenames();
		});
	}

	void dismissPendingRename(String oldName)
	{
		clientThread.invoke(() ->
		{
			renames.dismiss(oldName);
			pushPendingRenames();
		});
	}

	/**
	 * The Stats tab - every few ticks rather than every tick, it's a whole table.
	 */
	private void pushPerformance()
	{
		boolean enabled = config.enablePerformanceTracker();
		List<PerformanceRound> rounds = performance.snapshot();
		onSwing(() -> panel.updatePerformance(rounds, enabled));
	}

	/**
	 * The Stats tab's Reset button (EDT).
	 */
	void resetPerformance()
	{
		clientThread.invoke(() ->
		{
			performance.reset();
			pushPerformance();
		});
	}

	private void pushLocationMapper()
	{
		LocationTracker l = locations;
		WorldPoint position = l.getPosition();
		Integer level = l.getWildernessLevel();
		String name = l.getLocationName();
		List<String> overlaps = l.getOverlaps();
		WorldPoint hovered = l.getHoveredPoint();
		String hoveredName = l.getHoveredLocationName();
		WorldPoint cornerA = l.getCornerA();
		WorldPoint cornerB = l.getCornerB();
		onSwing(() -> panel.updateLocationMapper(position, level, name, overlaps, hovered, hoveredName, cornerA, cornerB));
	}

	private void pushWildernessLocations()
	{
		List<WildernessLocation> list = locations.getStore().getLocations();
		int hidden = locations.getStore().getHiddenCount();
		onSwing(() -> panel.updateWildernessLocations(list, hidden));
	}

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

		Deque<FriendsChatLogEntry> snapshot = new ArrayDeque<>(friendsChatLog);
		onSwing(() -> panel.updateFriendsChatLog(snapshot));
	}

	/**
	 * Friends chat members not on screen, as "Name (W308)", in FC list order. You're left out.
	 */
	private List<String> computeMissingMembers()
	{
		List<String> missing = new ArrayList<>();
		FriendsChatManager friendsChat = client.getFriendsChatManager();
		FriendsChatMember[] members = friendsChat != null ? friendsChat.getMembers() : null;
		if (members == null)
		{
			return missing;
		}

		Set<String> onScreen = new HashSet<>();
		for (Player player : client.getPlayers())
		{
			String standard = team.standardName(player);
			if (standard != null)
			{
				onScreen.add(standard);
			}
		}
		String you = team.standardName(client.getLocalPlayer());

		for (FriendsChatMember member : members)
		{
			if (member == null || member.getName() == null)
			{
				continue;
			}
			String standard = Text.standardize(member.getName());
			if (!standard.equals(you) && !onScreen.contains(standard))
			{
				missing.add(member.getName() + " (W" + member.getWorld() + ")");
			}
		}
		return missing;
	}

	/**
	 * You plus every FC/clan member on screen, sorted by player id - so the min and max (see
	 * MENU_ORDER_PID_INVESTIGATION.md) sit at the ends.
	 */
	private List<ClanIdRow> computeClanIdRows(Set<String> onScreenCallers)
	{
		List<ClanIdRow> rows = new ArrayList<>();
		Player local = client.getLocalPlayer();
		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null || !team.isChatMember(player))
			{
				continue;
			}
			String standard = team.standardName(player);
			boolean caller = onScreenCallers.contains(standard);
			rows.add(new ClanIdRow(player.getName(), caller ? highlights.nicknameFor(standard) : null, player.getId(),
				player == local, caller));
		}
		rows.sort(Comparator.comparingInt(row -> row.id));
		return rows;
	}

	/**
	 * On-screen teammates (you included) by the style of their wielded weapon.
	 */
	private ClanWeaponSummary computeClanWeapons()
	{
		List<String> melee = new ArrayList<>();
		List<String> ranged = new ArrayList<>();
		List<String> magic = new ArrayList<>();
		int unknown = 0;
		for (Player player : client.getPlayers())
		{
			if (!team.isOwnTeam(player))
			{
				continue;
			}

			String name = Text.removeTags(player.getName());
			switch (FightInfoTracker.weaponStyle(player))
			{
				case MELEE:
					melee.add(name);
					break;
				case RANGED:
					ranged.add(name);
					break;
				case MAGIC:
					magic.add(name);
					break;
				default:
					unknown++;
					break;
			}
		}
		melee.sort(String.CASE_INSENSITIVE_ORDER);
		ranged.sort(String.CASE_INSENSITIVE_ORDER);
		magic.sort(String.CASE_INSENSITIVE_ORDER);
		return new ClanWeaponSummary(melee, ranged, magic, unknown);
	}

	// ============================================================================================
	// Team capes and member count infoboxes
	// ============================================================================================

	private void updateTeamCapeCounts()
	{
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
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.forEach(e -> sorted.put(e.getKey(), e.getValue()));
		teamCapeCounts = sorted;
	}

	/**
	 * Nearby FC/clan member counts, like the vanilla Team plugin's: you're not counted, and an
	 * infobox only exists while its count is above zero.
	 */
	private void updateMemberCountInfoBoxes()
	{
		int friendsChatCount = 0;
		int clanCount = 0;
		Player local = client.getLocalPlayer();
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local)
			{
				continue;
			}
			if (player.isFriendsChatMember())
			{
				friendsChatCount++;
			}
			if (player.isClanMember())
			{
				clanCount++;
			}
		}

		friendsChatCountInfoBox = updateMemberCountInfoBox(friendsChatCountInfoBox,
			config.showFriendsChatMemberCount() ? friendsChatCount : 0, SpriteID.SideIcons.FRIENDS_CHAT, "friends chat");
		clanCountInfoBox = updateMemberCountInfoBox(clanCountInfoBox,
			config.showClanMemberCount() ? clanCount : 0, SpriteID.SideiconsInterface.CLAN, "clan");
	}

	private MemberCountInfoBox updateMemberCountInfoBox(MemberCountInfoBox infoBox, int count, int spriteId, String label)
	{
		if (count <= 0)
		{
			if (infoBox != null)
			{
				infoBoxManager.removeInfoBox(infoBox);
			}
			return null;
		}

		if (infoBox == null)
		{
			BufferedImage image = spriteManager.getSprite(spriteId, 0);
			if (image == null)
			{
				// Sprite not loaded yet - next tick.
				return null;
			}
			infoBox = new MemberCountInfoBox(image, this, label);
			infoBoxManager.addInfoBox(infoBox);
		}
		infoBox.setCount(count);
		return infoBox;
	}

	private void removeMemberCountInfoBoxes()
	{
		if (friendsChatCountInfoBox != null)
		{
			infoBoxManager.removeInfoBox(friendsChatCountInfoBox);
			friendsChatCountInfoBox = null;
		}
		if (clanCountInfoBox != null)
		{
			infoBoxManager.removeInfoBox(clanCountInfoBox);
			clanCountInfoBox = null;
		}
	}

	// ============================================================================================
	// Misc
	// ============================================================================================

	/**
	 * The Menu section's Attack priority used to be one dropdown of Target/Pile/Snipe orderings
	 * (key attackPriorityOrder, values like PILE_TARGET_SNIPE). It's now four 1st to 4th dropdowns -
	 * carry an old choice over once, attacker last, then drop the old key.
	 */
	private void migrateAttackPriorityOrder()
	{
		String old = configManager.getConfiguration(PrepToolsConfig.GROUP, "attackPriorityOrder");
		if (old == null)
		{
			return;
		}

		String[] parts = old.split("_");
		if (parts.length == 3 && configManager.getConfiguration(PrepToolsConfig.GROUP, "attackPriority1") == null)
		{
			try
			{
				for (int i = 0; i < 3; i++)
				{
					configManager.setConfiguration(PrepToolsConfig.GROUP, "attackPriority" + (i + 1), AttackCategory.valueOf(parts[i]));
				}
				configManager.setConfiguration(PrepToolsConfig.GROUP, "attackPriority4", AttackCategory.ATTACKER);
			}
			catch (IllegalArgumentException e)
			{
				log.warn("PvP Tools: couldn't migrate attackPriorityOrder '{}'", old);
			}
		}
		configManager.unsetConfiguration(PrepToolsConfig.GROUP, "attackPriorityOrder");
	}

	/**
	 * The sidebar button's icon, drawn rather than bundled - the plugin ships no image files.
	 */
	private static BufferedImage createNavIcon()
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
}
