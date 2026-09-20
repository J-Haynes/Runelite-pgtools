package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup(PrepToolsConfig.GROUP)
public interface PrepToolsConfig extends Config
{
	String GROUP = "preptools";

	// --- Callers section ---

	@ConfigSection(
		name = "Callers",
		description = "Who to highlight as callers, and how they look.",
		position = 10,
		closedByDefault = true
	)
	String callersSection = "callersSection";

	@ConfigItem(
		keyName = "callers",
		name = "Callers",
		description = "<html>Names to watch, comma or newline separated. List order<br>"
			+ "sets fallback priority - see the sidebar panel for full<br>"
			+ "details and to switch who's primary.<br><br>"
			+ "Add :Nickname after a name to display that instead of<br>"
			+ "their real name, e.g. 'Xx_Bob_xX:Bob'.</html>",
		position = 1,
		section = "callersSection"
	)
	default String callers()
	{
		return "";
	}

	@ConfigItem(
		keyName = "callerColor",
		name = "Caller colour",
		description = "Outline and minimap colour for the primary caller.",
		position = 2,
		section = "callersSection"
	)
	default Color callerColor()
	{
		return Color.GREEN;
	}

	@ConfigItem(
		keyName = "backupCallerColor",
		name = "Backup caller colour",
		description = "Outline colour for on-screen callers who aren't primary.",
		position = 3,
		section = "callersSection"
	)
	default Color backupCallerColor()
	{
		return Color.GRAY;
	}

	@ConfigItem(
		keyName = "showNames",
		name = "Show names",
		description = "Show a name tag next to every highlight.",
		position = 4,
		section = "callersSection"
	)
	default boolean showNames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "preferNicknameDisplay",
		name = "Show nicknames",
		description = "<html>Show a caller's nickname on screen instead of their real<br>"
			+ "name. Falls back to the real name for anyone without a<br>"
			+ "nickname set. Turn off to always show real names.</html>",
		position = 5,
		section = "callersSection"
	)
	default boolean preferNicknameDisplay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "colourAttackOption",
		name = "Colour menu options",
		description = "<html>Recolours the 'Attack' right-click menu option, and any<br>"
			+ "freeze spell casting option (Snare, Ice Barrage, etc.), to<br>"
			+ "match a highlighted player's colour - caller, target, pile<br>"
			+ "finder hit, or backup caller.</html>",
		position = 6,
		section = "callersSection"
	)
	default boolean colourAttackOption()
	{
		return true;
	}

	// --- Snipe List section ---

	@ConfigSection(
		name = "Snipe List",
		description = "Players to always highlight on sight, regardless of caller/target status.",
		position = 15,
		closedByDefault = true
	)
	String snipeListSection = "snipeListSection";

	@ConfigItem(
		keyName = "snipeList",
		name = "Snipe list",
		description = "<html>Names to always highlight, comma or newline separated.<br>"
			+ "Shift-right-click a player in game and choose 'Add to snipe<br>"
			+ "list' to add them without typing here.</html>",
		position = 1,
		section = "snipeListSection"
	)
	default String snipeList()
	{
		return "";
	}

	@ConfigItem(
		keyName = "snipeColor",
		name = "Snipe colour",
		description = "Outline colour for a player on the snipe list.",
		position = 2,
		section = "snipeListSection"
	)
	default Color snipeColor()
	{
		return Color.RED;
	}

	// --- Overlay section (shared rendering style for every highlight category) ---

	@ConfigSection(
		name = "Overlay",
		description = "<html>Shared appearance settings for every highlighted player -<br>"
			+ "caller, target, and pile finder hit all use the same shape,<br>"
			+ "outline weight, name font, and dot size. Colours stay<br>"
			+ "per-category, set in the Callers/Target/Pile Finder<br>"
			+ "sections instead.</html>",
		position = 5,
		closedByDefault = true
	)
	String overlaySection = "overlaySection";

	@ConfigItem(
		keyName = "highlightShape",
		name = "Highlight shape",
		description = "<html>EXPERIMENTAL. 'Shell' is the usual model outline. 'Blue<br>"
			+ "Dot Robot' instead draws a plain dot at the centre of the<br>"
			+ "model, using each player's own category colour (Caller/<br>"
			+ "Target/Pile Finder section) - size is set below. Applies to<br>"
			+ "every highlighted player.</html>",
		position = 1,
		section = "overlaySection"
	)
	default HighlightShape highlightShape()
	{
		return HighlightShape.SHELL;
	}

	@ConfigItem(
		keyName = "outlineWidth",
		name = "Outline weight",
		description = "Thickness of the highlight outline, in pixels, when Highlight shape is set to Shell.",
		position = 2,
		section = "overlaySection"
	)
	default int outlineWidth()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "nameFontStyle",
		name = "Name weight",
		description = "Font weight for every highlighted player's name tag.",
		position = 3,
		section = "overlaySection"
	)
	default NameFontStyle nameFontStyle()
	{
		return NameFontStyle.BOLD;
	}

	@ConfigItem(
		keyName = "nameFontSize",
		name = "Name size",
		description = "Font size, in points, for every highlighted player's name tag.",
		position = 4,
		section = "overlaySection"
	)
	default int nameFontSize()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "dotSize",
		name = "Dot size",
		description = "Diameter, in pixels, of the highlight dot when Highlight shape is set to Blue Dot Robot.",
		position = 5,
		section = "overlaySection"
	)
	default int dotSize()
	{
		return 12;
	}

	// --- Target section ---

	@ConfigSection(
		name = "Target",
		description = "The primary caller's target - who they're attacking or following.",
		position = 20,
		closedByDefault = true
	)
	String targetSection = "targetSection";

	@ConfigItem(
		keyName = "targetColor",
		name = "Target colour",
		description = "<html>Outline colour for the primary caller's target.<br>"
			+ "Always wins over the pile finder colour if a<br>"
			+ "player happens to be both.</html>",
		position = 1,
		section = "targetSection"
	)
	default Color targetColor()
	{
		return Color.BLUE;
	}

	@ConfigItem(
		keyName = "prioritiseTargetAttack",
		name = "Prioritise attack option",
		description = "<html>Forces 'Attack' to the top of the right-click menu<br>"
			+ "for the target, so they're always left-click<br>"
			+ "attackable even in a crowd.</html>",
		position = 2,
		section = "targetSection"
	)
	default boolean prioritiseTargetAttack()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTargetFlash",
		name = "Flash on new target",
		description = "Briefly flash a player when they first become the target.",
		position = 3,
		section = "targetSection"
	)
	default boolean showTargetFlash()
	{
		return false;
	}

	@ConfigItem(
		keyName = "targetFlashColor",
		name = "Flash colour",
		description = "Colour flashed when a new target first appears.",
		position = 4,
		section = "targetSection"
	)
	default Color targetFlashColor()
	{
		return Color.WHITE;
	}

	@ConfigItem(
		keyName = "targetFlashDurationMillis",
		name = "Flash duration (ms)",
		description = "How long the new-target flash lasts.",
		position = 5,
		section = "targetSection"
	)
	default int targetFlashDurationMillis()
	{
		return 1500;
	}

	// --- Targeted player (friends chat) section ---

	@ConfigSection(
		name = "Pile Finder",
		description = "Detects a player being piled on by your team, even if uncalled.",
		position = 30,
		closedByDefault = true
	)
	String targetedPlayerSection = "targetedPlayerSection";

	@ConfigItem(
		keyName = "enableTargetedPlayerDetection",
		name = "Enable pile finder",
		description = "<html>Highlights a player if enough of your friends chat<br>"
			+ "members are interacting with them at once - useful for<br>"
			+ "spotting a pile even if nobody explicitly called it.<br>"
			+ "Requires being in a friends chat.</html>",
		position = 1,
		section = "targetedPlayerSection"
	)
	default boolean enableTargetedPlayerDetection()
	{
		return true;
	}

	@ConfigItem(
		keyName = "targetedPlayerThreshold",
		name = "Pile threshold",
		description = "Minimum friends chat members interacting with the same player.",
		position = 2,
		section = "targetedPlayerSection"
	)
	default int targetedPlayerThreshold()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "targetedPlayerColor",
		name = "Pile colour",
		description = "Outline colour for a player caught by the pile finder.",
		position = 3,
		section = "targetedPlayerSection"
	)
	default Color targetedPlayerColor()
	{
		return new Color(160, 32, 240); // purple
	}

	@ConfigItem(
		keyName = "prioritiseAttackForPiles",
		name = "Prioritise attack option",
		description = "<html>Forces 'Attack' to the top of the right-click menu<br>"
			+ "for a player caught by the pile finder. The caller's<br>"
			+ "target (Target section) always wins over this if a<br>"
			+ "player happens to be both.</html>",
		position = 4,
		section = "targetedPlayerSection"
	)
	default boolean prioritiseAttackForPiles()
	{
		return true;
	}

	// --- Infobox section (bottom-right panel) ---

	@ConfigSection(
		name = "Infobox",
		description = "The bottom-right panel: your stats/freeze status, target's prayer, opponent prayers/weapons, and who's attacking you.",
		position = 1,
		closedByDefault = true
	)
	String fightInfoSection = "fightInfoSection";

	@ConfigItem(
		keyName = "showFightInfoOverlay",
		name = "Enable infobox",
		description = "<html>Master switch for the whole panel. Turning this off hides<br>"
			+ "everything below, regardless of their individual settings.</html>",
		position = 1,
		section = "fightInfoSection"
	)
	default boolean showFightInfoOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "compactMode",
		name = "Compact mode",
		description = "<html>Tighter layout: no section labels, every row gets a<br>"
			+ "small coloured accent stripe instead so the sections stay<br>"
			+ "easy to tell apart. Good for smaller screens.</html>",
		position = 2,
		section = "fightInfoSection"
	)
	default boolean compactMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPlayerHitpoints",
		name = "Show hitpoints",
		description = "<html>Part of 'Your Stats'. Shows your current/max hitpoints,<br>"
			+ "using the Hitpoints skill icon. Flashes red when your HP<br>"
			+ "falls below the threshold below.</html>",
		position = 3,
		section = "fightInfoSection"
	)
	default boolean showPlayerHitpoints()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPlayerPrayer",
		name = "Show prayer",
		description = "Part of 'Your Stats'. Shows your current prayer points, using the Prayer skill icon.",
		position = 4,
		section = "fightInfoSection"
	)
	default boolean showPlayerPrayer()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPlayerStrength",
		name = "Show strength",
		description = "Part of 'Your Stats'. Shows your current (boosted) Strength level, using the Strength skill icon.",
		position = 5,
		section = "fightInfoSection"
	)
	default boolean showPlayerStrength()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPlayerRanged",
		name = "Show ranged",
		description = "Part of 'Your Stats'. Shows your current (boosted) Ranged level, using the Ranged skill icon.",
		position = 6,
		section = "fightInfoSection"
	)
	default boolean showPlayerRanged()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hitpointsFlashThresholdPercent",
		name = "Low HP flash threshold (%)",
		description = "Flash the hitpoints value red when your HP falls below this percentage of max.",
		position = 7,
		section = "fightInfoSection"
	)
	default int hitpointsFlashThresholdPercent()
	{
		return 75;
	}

	@ConfigItem(
		keyName = "showFreezeStatus",
		name = "Show freeze status",
		description = "<html>Shows your own freeze status in the overlay - a faded<br>"
			+ "placeholder icon while unfrozen, or the active spell's own<br>"
			+ "icon and countdown while frozen. Uses the same countdown<br>"
			+ "logic and time format as the Freeze Timers section.</html>",
		position = 8,
		section = "fightInfoSection"
	)
	default boolean showFreezeStatus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTargetPrayer",
		name = "Show target's prayer",
		description = "Shows the primary caller's target's active overhead prayer icon.",
		position = 9,
		section = "fightInfoSection"
	)
	default boolean showTargetPrayer()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showOpponentPrayers",
		name = "Show opponent prayers",
		description = "Shows aggregate overhead prayer counts for every player on screen who is NOT in your friends chat.",
		position = 10,
		section = "fightInfoSection"
	)
	default boolean showOpponentPrayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOpponentWeapons",
		name = "Show opponent weapons",
		description = "Shows aggregate weapon counts for every player on screen who is NOT in your friends chat.",
		position = 11,
		section = "fightInfoSection"
	)
	default boolean showOpponentWeapons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showAttackingYou",
		name = "Show weapons attacking you",
		description = "Shows a breakdown of tracked weapons being used by whoever is currently interacting with you.",
		position = 12,
		section = "fightInfoSection"
	)
	default boolean showAttackingYou()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overlayBackgroundColor",
		name = "Background colour",
		description = "Background colour behind the fight info panel.",
		position = 13,
		section = "fightInfoSection"
	)
	@Alpha
	default Color overlayBackgroundColor()
	{
		return new Color(0x5B, 0x5B, 0x5B, 0x5B);
	}

	@ConfigItem(
		keyName = "showAttackerFlash",
		name = "Flash on new attacker",
		description = "Briefly flash the panel when someone new starts attacking you.",
		position = 14,
		section = "fightInfoSection"
	)
	default boolean showAttackerFlash()
	{
		return true;
	}

	@ConfigItem(
		keyName = "attackerFlashColor",
		name = "Flash colour",
		description = "Colour flashed when a new attacker engages you.",
		position = 15,
		section = "fightInfoSection"
	)
	default Color attackerFlashColor()
	{
		return Color.YELLOW;
	}

	@ConfigItem(
		keyName = "attackerFlashDurationMillis",
		name = "Flash duration (ms)",
		description = "How long the new-attacker flash lasts.",
		position = 16,
		section = "fightInfoSection"
	)
	default int attackerFlashDurationMillis()
	{
		return 1500;
	}

	@ConfigItem(
		keyName = "strengthPeakColor",
		name = "Strength boost colour",
		description = "<html>Part of 'Your Stats'. Colour for the Strength number<br>"
			+ "while your current Strength boost (boosted level minus real<br>"
			+ "level) is above the 'Strength green threshold' setting<br>"
			+ "below. White at or below that threshold.</html>",
		position = 17,
		section = "fightInfoSection"
	)
	default Color strengthPeakColor()
	{
		return Color.GREEN;
	}

	@ConfigItem(
		keyName = "strengthGreenThreshold",
		name = "Strength green threshold",
		description = "<html>How big your current Strength boost (boosted level<br>"
			+ "minus real level) must be to show green - white once it<br>"
			+ "drops to this value or below. E.g. with this set to 10, a<br>"
			+ "+11 boost (110/99) shows green, and it turns white the<br>"
			+ "moment it decays to a +10 boost (109/99). 0 (default) means<br>"
			+ "any active boost at all - even +1 - shows green.</html>",
		position = 18,
		section = "fightInfoSection"
	)
	default int strengthGreenThreshold()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "prayerBoostColor",
		name = "Prayer boost colour",
		description = "<html>Part of 'Your Stats'. Colour for the Prayer number<br>"
			+ "whenever current prayer points are above your real<br>"
			+ "(unboosted) Prayer level - not just from a prayer potion,<br>"
			+ "but also from gear that restores prayer points past the<br>"
			+ "normal cap, like the Ancient mace or the Eldritch<br>"
			+ "nightmare staff. White at or below your real level.</html>",
		position = 19,
		section = "fightInfoSection"
	)
	default Color prayerBoostColor()
	{
		return Color.GREEN;
	}

	// --- Team Capes section ---

	@ConfigSection(
		name = "Team Capes",
		description = "Custom names for team cape numbers, shown alongside the vanilla team cape counter.",
		position = 40,
		closedByDefault = true
	)
	String teamCapeSection = "teamCapeSection";

	@ConfigItem(
		keyName = "showTeamCapeOverlay",
		name = "Show team cape overlay",
		description = "Shows a small panel listing nearby team cape numbers, with your custom name for each.",
		position = 1,
		section = "teamCapeSection"
	)
	default boolean showTeamCapeOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "teamCapeNames",
		name = "Team cape names",
		description = "<html>One 'TeamNumber:Name' entry per line, e.g.<br>"
			+ "'50:EoP' shows 'EoP' next to anyone wearing team cape 50.<br>"
			+ "Team numbers without an entry here just show the icon alone.<br>"
			+ "Names longer than 5 characters are truncated - easier to<br>"
			+ "manage from the sidebar panel's Team Capes editor, which<br>"
			+ "enforces that limit as you type.</html>",
		position = 2,
		section = "teamCapeSection"
	)
	default String teamCapeNames()
	{
		return "50:EoP\n38:Apex";
	}

	@ConfigItem(
		keyName = "minimumTeamCapeCount",
		name = "Minimum count",
		description = "Only show a team number once at least this many nearby players are wearing it.",
		position = 3,
		section = "teamCapeSection"
	)
	default int minimumTeamCapeCount()
	{
		return 2;
	}

	// --- Friends Chat Log section ---

	@ConfigSection(
		name = "Friends Chat Log",
		description = "A newest-first log of friends chat join/leave/death activity, shown in the sidebar panel.",
		position = 45,
		closedByDefault = true
	)
	String friendsChatLogSection = "friendsChatLogSection";

	@ConfigItem(
		keyName = "enableFriendsChatLog",
		name = "Enable log",
		description = "Logs friends chat members entering/exiting chat and dying, with a timestamp, in the sidebar panel.",
		position = 1,
		section = "friendsChatLogSection"
	)
	default boolean enableFriendsChatLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "friendsChatLogMaxEntries",
		name = "Max log entries",
		description = "Oldest entries are dropped once the log reaches this many lines.",
		position = 2,
		section = "friendsChatLogSection"
	)
	default int friendsChatLogMaxEntries()
	{
		return 200;
	}

	// --- Chat spam section ---

	@ConfigSection(
		name = "Chat Spam",
		description = "Quickly post the name of whoever you're following into chat.",
		position = 50,
		closedByDefault = true
	)
	String chatSpamSection = "chatSpamSection";

	@ConfigItem(
		keyName = "chatSpamHotkey",
		name = "Hotkey",
		description = "<html>While following/interacting with a player, pressing this<br>"
			+ "types their name into chat. You still press Enter yourself.</html>",
		position = 1,
		section = "chatSpamSection"
	)
	default Keybind chatSpamHotkey()
	{
		return new Keybind(KeyEvent.VK_F12, 0);
	}

	@ConfigItem(
		keyName = "chatSpamPrefix",
		name = "Prefix",
		description = "<html>Text placed before the name, e.g. 'EoP' produces<br>'EoP Matt M'.</html>",
		position = 2,
		section = "chatSpamSection"
	)
	default String chatSpamPrefix()
	{
		return "";
	}

	// --- Location Tools section ---

	@ConfigSection(
		name = "Location Tools",
		description = "<html>Detects roughly where in the wilderness you are (from a<br>"
			+ "user-built list of named areas) and lets you spam it into<br>"
			+ "chat. Also holds the location-mapping tool in the sidebar<br>"
			+ "panel, for building/editing that list from your own<br>"
			+ "in-game coordinates.</html>",
		position = 55,
		closedByDefault = true
	)
	String locationSection = "locationSection";

	@ConfigItem(
		keyName = "showLocationInInfobox",
		name = "Show location in infobox",
		description = "Shows your current wilderness location name and level (if any) in the fight info panel.",
		position = 1,
		section = "locationSection"
	)
	default boolean showLocationInInfobox()
	{
		return true;
	}

	@ConfigItem(
		keyName = "locationSpamHotkey",
		name = "Location hotkey",
		description = "<html>Pressing this types your current wilderness location<br>"
			+ "into chat. You still press Enter yourself.</html>",
		position = 2,
		section = "locationSection"
	)
	default Keybind locationSpamHotkey()
	{
		return new Keybind(KeyEvent.VK_F11, 0);
	}

	@ConfigItem(
		keyName = "locationSpamPrefix",
		name = "Prefix",
		description = "<html>Text placed before the location, e.g. 'Loc:' produces<br>'Loc: Ferox Enclave (level 15)'.</html>",
		position = 3,
		section = "locationSection"
	)
	default String locationSpamPrefix()
	{
		return "";
	}

	// --- Teleblock Timers section ---

	@ConfigSection(
		name = "Teleblock Timers",
		description = "Countdown shown when a player - including you - gets tele-blocked.",
		position = 75,
		closedByDefault = true
	)
	String teleblockSection = "teleblockSection";

	@ConfigItem(
		keyName = "showTeleblockTimers",
		name = "Show teleblock timers",
		description = "<html>Shows an icon and countdown at the base of a tele-blocked<br>"
			+ "player's model - works on other players and on yourself.<br>"
			+ "5 minutes normally, or 2.5 minutes if the target was<br>"
			+ "praying Protect from Magic at the moment it landed.</html>",
		position = 1,
		section = "teleblockSection"
	)
	default boolean showTeleblockTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "teleblockTimerColor",
		name = "Teleblock colour",
		description = "Colour of the countdown text while tele-blocked.",
		position = 2,
		section = "teleblockSection"
	)
	default Color teleblockTimerColor()
	{
		return new Color(255, 140, 0); // orange
	}

	@ConfigItem(
		keyName = "teleblockTimeFormat",
		name = "Time format",
		description = "Show the countdown in seconds or game ticks.",
		position = 3,
		section = "teleblockSection"
	)
	default FreezeTimeFormat teleblockTimeFormat()
	{
		return FreezeTimeFormat.SECONDS;
	}

	@ConfigItem(
		keyName = "hideTeleblockTimersForOwnTeam",
		name = "Hide for own team",
		description = "Don't show teleblock timers for your own team - your configured callers and anyone in your friends chat.",
		position = 4,
		section = "teleblockSection"
	)
	default boolean hideTeleblockTimersForOwnTeam()
	{
		return false;
	}

	// --- Debug section ---

	@ConfigSection(
		name = "Debug",
		description = "Diagnostic logging - only needed if something isn't working.",
		position = 100,
		closedByDefault = true
	)
	String debugSection = "debugSection";

	@ConfigItem(
		keyName = "debugLogging",
		name = "Debug logging",
		description = "<html>Logs weapon detection and right-click menu contents to<br>"
			+ "the console/log file. Turn on temporarily to diagnose<br>"
			+ "issues, then off.</html>",
		position = 1,
		section = "debugSection"
	)
	default boolean debugLogging()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showWildernessLocationOverlay",
		name = "Show location boxes",
		description = "<html>Helper for building the wilderness location list (see the<br>"
			+ "sidebar's Location Mapper tool). Draws every saved area's<br>"
			+ "outer border on your current plane, labelled with its name.<br>"
			+ "Purely a mapping aid - functional, not pretty. Turn on<br>"
			+ "temporarily while mapping, then off.</html>",
		position = 2,
		section = "debugSection"
	)
	default boolean showWildernessLocationOverlay()
	{
		return false;
	}

	// --- Freeze timers section ---

	@ConfigSection(
		name = "Freeze Timers",
		description = "Countdown shown when a player - including you - gets frozen.",
		position = 70,
		closedByDefault = true
	)
	String freezeSection = "freezeSection";

	@ConfigItem(
		keyName = "showFreezeTimers",
		name = "Show freeze timers",
		description = "<html>Shows an icon and countdown at the base of a frozen<br>"
			+ "player's model when hit by Bind, Snare, Entangle, or an Ice<br>"
			+ "spell - works on other players and on yourself.<br><br>"
			+ "Uses each spell's base duration. Gear that extends freeze<br>"
			+ "time (swampbark armour, ancient sceptre) isn't accounted<br>"
			+ "for, so an actual freeze may outlast the shown countdown.</html>",
		position = 1,
		section = "freezeSection"
	)
	default boolean showFreezeTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "freezeTimerColor",
		name = "Freeze colour",
		description = "Colour of the countdown text while actually frozen.",
		position = 2,
		section = "freezeSection"
	)
	default Color freezeTimerColor()
	{
		return new Color(120, 200, 255); // icy blue
	}

	@ConfigItem(
		keyName = "immunityTimerColor",
		name = "Immunity colour",
		description = "<html>Colour of the countdown text during the brief window after<br>"
			+ "a freeze wears off, during which the player can't be frozen<br>"
			+ "again (5 ticks / 3 seconds). Same icon as the freeze itself,<br>"
			+ "just this colour, so the two phases are easy to tell apart.</html>",
		position = 3,
		section = "freezeSection"
	)
	default Color immunityTimerColor()
	{
		return Color.RED;
	}

	@ConfigItem(
		keyName = "freezeTimeFormat",
		name = "Time format",
		description = "Show the countdown in seconds (e.g. 19.2s) or game ticks (e.g. 32).",
		position = 4,
		section = "freezeSection"
	)
	default FreezeTimeFormat freezeTimeFormat()
	{
		return FreezeTimeFormat.SECONDS;
	}

	@ConfigItem(
		keyName = "hideFreezeTimersForOwnTeam",
		name = "Hide for own team",
		description = "<html>Don't show freeze timers for your own team - your configured<br>"
			+ "callers and anyone in your friends chat - so AoE freezes<br>"
			+ "landing on teammates don't clutter the screen.</html>",
		position = 5,
		section = "freezeSection"
	)
	default boolean hideFreezeTimersForOwnTeam()
	{
		return false;
	}

	@ConfigItem(
		keyName = "preventFreezingTeammates",
		name = "Prevent freezing teammates",
		description = "<html>Removes Bind/Snare/Entangle/Ice spell casting options<br>"
			+ "from the right-click menu when the target is your own<br>"
			+ "team - a configured caller or a friends chat member - so<br>"
			+ "a freezer can't accidentally freeze or immunise a<br>"
			+ "teammate mid-fight.</html>",
		position = 6,
		section = "freezeSection"
	)
	default boolean preventFreezingTeammates()
	{
		return true;
	}
}
