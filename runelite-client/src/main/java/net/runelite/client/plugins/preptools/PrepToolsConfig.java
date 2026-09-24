package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(PrepToolsConfig.GROUP)
public interface PrepToolsConfig extends Config
{
	String GROUP = "preptools";

	// --- Callers section ---

	@ConfigSection(
		name = "Callers",
		description = "Who to highlight as callers, and how they look.",
		position = 3,
		closedByDefault = true
	)
	String callersSection = "callersSection";

	// hidden = true on every list the sidebar panel edits (callers, snipe list, team cape names,
	// spam messages). RuneLite's settings panel saves a text field on focus-lost with whatever text
	// it loaded when it was opened, and never refreshes when the value changes elsewhere - so
	// switching sidebar -> settings -> sidebar silently wrote the stale text back over any
	// sidebar edits made in between (reported as "switching between the sidebar and settings can
	// destroy the caller list"). The sidebar is the only editor for these now.
	@ConfigItem(
		keyName = "callers",
		name = "Callers",
		description = "<html>Names to watch, one per line, edited from the sidebar panel.<br>"
			+ "Format per line: 'Name', 'Name:Nickname', and a leading '!'<br>"
			+ "marks a caller as disabled (kept in the list, but ignored).</html>",
		position = 1,
		section = "callersSection",
		hidden = true
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
			+ "match a highlighted player's colour - target, pile finder<br>"
			+ "hit, or snipe. Callers get their 'Follow' option coloured<br>"
			+ "instead (see below).</html>",
		position = 6,
		section = "callersSection"
	)
	default boolean colourAttackOption()
	{
		return true;
	}

	@ConfigItem(
		keyName = "prioritiseCallerFollow",
		name = "Prioritise Follow on callers",
		description = "<html>Makes 'Follow' the left-click option on a caller (primary<br>"
			+ "or backup) and colours it with their caller colour, instead<br>"
			+ "of colouring 'Attack'. A target/pile/snipe Attack on the<br>"
			+ "same tile still wins.</html>",
		position = 7,
		section = "callersSection"
	)
	default boolean prioritiseCallerFollow()
	{
		return true;
	}

	// --- Snipe List section ---

	@ConfigSection(
		name = "Snipe List",
		description = "Players to always highlight on sight, regardless of caller/target status.",
		position = 6,
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
		section = "snipeListSection",
		hidden = true
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

	@ConfigItem(
		keyName = "prioritiseAttackForSnipes",
		name = "Prioritise attack option",
		description = "<html>Forces 'Attack' to the top of the right-click menu<br>"
			+ "for a snipe list hit, so they're always left-click<br>"
			+ "attackable even in a crowd. See the Menu section's<br>"
			+ "'Attack priority order' if a target/pile and a snipe<br>"
			+ "ever land on the same tile.</html>",
		position = 3,
		section = "snipeListSection"
	)
	default boolean prioritiseAttackForSnipes()
	{
		return true;
	}

	// --- Overlay section (shared rendering style for every highlight category) ---

	@ConfigSection(
		name = "Overlay",
		description = "<html>Shared appearance settings for every highlighted player -<br>"
			+ "caller, target, and pile finder hit all use the same shape,<br>"
			+ "outline weight, name font, and dot size. Colours stay<br>"
			+ "per-category, set in the Callers/Target/Pile Finder<br>"
			+ "sections instead.</html>",
		position = 2,
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
		position = 4,
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

	// --- Menu section ---

	@ConfigSection(
		name = "Menu",
		description = "Right-click menu tweaks that aren't tied to callers/targets specifically.",
		position = 7,
		closedByDefault = true
	)
	String menuSection = "menuSection";

	@ConfigItem(
		keyName = "shiftClickWalkUnderAttackablePlayers",
		name = "Shift-click Walk here",
		description = "<html>Shift-click an attackable player to walk under/through them<br>"
			+ "instead of attacking - same idea as RuneLite's own shift-click<br>"
			+ "Walk here for NPCs/objects (Menu Entry Swapper), extended to<br>"
			+ "players you could attack, which vanilla RuneLite doesn't support.</html>",
		position = 1,
		section = "menuSection"
	)
	default boolean shiftClickWalkUnderAttackablePlayers()
	{
		return false;
	}

	@ConfigItem(
		keyName = "attackPriorityOrder",
		name = "Attack priority order",
		description = "<html>When a target, a pile hit, and a snipe list hit all land<br>"
			+ "on the same tile, this decides which one's 'Attack' wins the<br>"
			+ "top of the menu. Each category's own prioritisation can still<br>"
			+ "be turned off individually (Target section, Pile Finder<br>"
			+ "section, Snipe List section) - this only breaks the tie<br>"
			+ "between whichever categories are actually still enabled.</html>",
		position = 2,
		section = "menuSection"
	)
	default AttackPriorityOrder attackPriorityOrder()
	{
		return AttackPriorityOrder.TARGET_PILE_SNIPE;
	}

	@ConfigItem(
		keyName = "hideAttackOnTeammates",
		name = "Hide Attack on teammates",
		description = "<html>Removes 'Attack' from the menu when the player is your<br>"
			+ "own team - a configured caller, friends chat or clan member.<br>"
			+ "Stops a mis-click on whoever is leading the fall-in (or<br>"
			+ "anyone else stacked in your pile) from attacking them.</html>",
		position = 3,
		section = "menuSection"
	)
	default boolean hideAttackOnTeammates()
	{
		return true;
	}

	// --- Targeted player (friends chat) section ---

	@ConfigSection(
		name = "Pile Finder",
		description = "Detects a player being piled on by your team, even if uncalled.",
		position = 5,
		closedByDefault = true
	)
	String targetedPlayerSection = "targetedPlayerSection";

	@ConfigItem(
		keyName = "enableTargetedPlayerDetection",
		name = "Enable pile finder",
		description = "<html>Highlights a player if enough of your team (friends chat<br>"
			+ "or clan members, plus you) are interacting with them at<br>"
			+ "once - useful for spotting a pile even if nobody explicitly<br>"
			+ "called it.</html>",
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
		description = "Minimum team members (friends chat/clan, plus you) interacting with the same player.",
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
		description = "Shows aggregate overhead prayer counts for every player on screen who is NOT on your team (friends chat, clan, or a caller).",
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
		description = "Shows aggregate weapon counts for every player on screen who is NOT on your team (friends chat, clan, or a caller).",
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

	@ConfigItem(
		keyName = "showPlayerIndex",
		name = "Show player index",
		description = "<html>Shows your current server-assigned player index - a<br>"
			+ "stable ID given at login, NOT the same as the PID that<br>"
			+ "shuffles every ~30-90s. Jagex has confirmed (Mod Ash, Feb<br>"
			+ "2023) the client uses this ID to decide render priority<br>"
			+ "when multiple players stand on one tile - who's drawn on<br>"
			+ "top vs hidden in a pile. Informational only: which<br>"
			+ "direction (low/high) is favourable isn't confirmed<br>"
			+ "anywhere, so this doesn't colour-code the value or claim<br>"
			+ "it controls who gets right/left-clicked in a pile.</html>",
		position = 20,
		section = "fightInfoSection"
	)
	default boolean showPlayerIndex()
	{
		return false;
	}

	// --- Team Capes section ---

	@ConfigSection(
		name = "Team Capes",
		description = "Custom names for team cape numbers, shown alongside the vanilla team cape counter.",
		position = 8,
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
		section = "teamCapeSection",
		hidden = true
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

	@ConfigItem(
		keyName = "showFriendsChatMemberCount",
		name = "Friends chat count",
		description = "<html>Infobox counting friends chat members near you (in your<br>"
			+ "loaded game view, not counting yourself) - same as the<br>"
			+ "vanilla Team plugin's counter.</html>",
		position = 4,
		section = "teamCapeSection"
	)
	default boolean showFriendsChatMemberCount()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showClanMemberCount",
		name = "Clan count",
		description = "<html>Infobox counting clan members near you (in your loaded<br>"
			+ "game view, not counting yourself) - same as the vanilla Team<br>"
			+ "plugin's counter.</html>",
		position = 5,
		section = "teamCapeSection"
	)
	default boolean showClanMemberCount()
	{
		return true;
	}

	// --- Friends Chat Log section ---

	@ConfigSection(
		name = "Friends Chat Log",
		description = "A newest-first log of friends chat join/leave/death activity, shown in the sidebar panel.",
		position = 9,
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

	// --- Pile Spam section --- (renamed from "Chat Spam"; keyNames below deliberately kept as
	// chatSpamHotkey/chatSpamPrefix so anyone who already configured a custom hotkey/prefix doesn't
	// lose it - only the section/method names, and the removed channel option, actually changed)

	@ConfigSection(
		name = "Pile Spam",
		description = "Quickly post the name of whoever you're following/piling into public chat.",
		position = 10,
		closedByDefault = true
	)
	String pileSpamSection = "pileSpamSection";

	@ConfigItem(
		keyName = "chatSpamHotkey",
		name = "Hotkey",
		description = "<html>While following/interacting with a player, pressing this<br>"
			+ "sends their name straight to public/game chat.</html>",
		position = 1,
		section = "pileSpamSection"
	)
	default Keybind pileSpamHotkey()
	{
		return new Keybind(KeyEvent.VK_F12, 0);
	}

	@ConfigItem(
		keyName = "chatSpamPrefix",
		name = "Prefix",
		description = "<html>Text placed before the name, e.g. 'EoP' produces<br>'EoP Matt M'.</html>",
		position = 2,
		section = "pileSpamSection"
	)
	default String pileSpamPrefix()
	{
		return "";
	}

	// --- Location Spam section ---

	@ConfigSection(
		name = "Location Spam",
		description = "<html>Detects roughly where in the wilderness you are (from a<br>"
			+ "user-built list of named areas) and lets you spam it into<br>"
			+ "chat. Also holds the location-mapping tool in the sidebar<br>"
			+ "panel, for building/editing that list from your own<br>"
			+ "in-game coordinates.</html>",
		position = 11,
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
		description = "<html>Pressing this sends your current wilderness location straight<br>"
			+ "to chat, formatted as 'Location: Ferox Enclave, 15'.</html>",
		position = 2,
		section = "locationSection"
	)
	default Keybind locationSpamHotkey()
	{
		return new Keybind(KeyEvent.VK_F11, 0);
	}

	@ConfigItem(
		keyName = "locationSpamChannel",
		name = "Channel",
		description = "Which chat channel the hotkey sends to.",
		position = 3,
		section = "locationSection"
	)
	default ChatSpamChannel locationSpamChannel()
	{
		return ChatSpamChannel.FRIENDS_CHAT;
	}

	// --- Spammer section ---

	@ConfigSection(
		name = "Spammer",
		description = "<html>A hotkey that sends one of your saved messages to public<br>"
			+ "chat. Messages are added/selected from the sidebar's Spam tab.</html>",
		position = 12,
		closedByDefault = true
	)
	String spammerSection = "spammerSection";

	@ConfigItem(
		keyName = "spamHotkey",
		name = "Hotkey",
		description = "Pressing this sends the selected (or a random) saved message to public chat - at most once every 5 seconds.",
		position = 1,
		section = "spammerSection"
	)
	default Keybind spamHotkey()
	{
		return new Keybind(KeyEvent.VK_F10, 0);
	}

	@ConfigItem(
		keyName = "spamRandom",
		name = "Random message",
		description = "<html>Send a random saved message each press (never the same one<br>"
			+ "twice in a row) instead of the selected one. Also toggleable<br>"
			+ "from the sidebar's Spam tab.</html>",
		position = 2,
		section = "spammerSection"
	)
	default boolean spamRandom()
	{
		return false;
	}

	@ConfigItem(
		keyName = "spamMessages",
		name = "Messages",
		description = "Saved spam messages, one per line - edited from the sidebar's Spam tab.",
		position = 3,
		section = "spammerSection",
		hidden = true
	)
	default String spamMessages()
	{
		return "";
	}

	@ConfigItem(
		keyName = "selectedSpamMessage",
		name = "Selected message",
		description = "The saved message the hotkey sends when Random is off - chosen from the sidebar.",
		position = 4,
		section = "spammerSection",
		hidden = true
	)
	default String selectedSpamMessage()
	{
		return "";
	}

	// --- Multi Lines section ---

	@ConfigSection(
		name = "Multi Lines",
		description = "<html>Draws the borders of multi-combat areas, and the level 20/30<br>"
			+ "lines, on the ground.</html>",
		position = 13,
		closedByDefault = true
	)
	String multiLinesSection = "multiLinesSection";

	@ConfigItem(
		keyName = "showMultiLines",
		name = "Show multi lines",
		description = "<html>Outlines every wilderness multi-combat area around you, out<br>"
			+ "to the GPU plugin's draw distance (data from the Wilderness<br>"
			+ "Lines plugin). See below to show them outside the wilderness<br>"
			+ "too.</html>",
		position = 1,
		section = "multiLinesSection"
	)
	default boolean showMultiLines()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "multiLinesColor",
		name = "Multi lines colour",
		description = "Colour of the lines bordering multi-combat areas.",
		position = 2,
		section = "multiLinesSection"
	)
	default Color multiLinesColor()
	{
		return Color.RED;
	}

	@ConfigItem(
		keyName = "showSpearLines",
		name = "Show spear lines",
		description = "<html>Also outline the area you could be dragon-speared (knocked<br>"
			+ "back) from into a multi-combat area.</html>",
		position = 3,
		section = "multiLinesSection"
	)
	default boolean showSpearLines()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "spearLinesColor",
		name = "Spear lines colour",
		description = "Colour of the lines bordering the spear range around multi-combat areas.",
		position = 4,
		section = "multiLinesSection"
	)
	default Color spearLinesColor()
	{
		return Color.ORANGE;
	}

	@ConfigItem(
		keyName = "showMultiLinesOutsideWilderness",
		name = "Show outside the wilderness",
		description = "<html>Also draw multi (and spear) lines for multi-combat areas<br>"
			+ "outside the wilderness - data from the Multi-Lines plugin.</html>",
		position = 5,
		section = "multiLinesSection"
	)
	default boolean showMultiLinesOutsideWilderness()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showTwentyLine",
		name = "Show 20 line",
		description = "<html>Line between wilderness levels 20 and 21 - most teleports<br>"
			+ "stop working above it. Also drawn in the rev and slayer caves.</html>",
		position = 6,
		section = "multiLinesSection"
	)
	default boolean showTwentyLine()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "twentyLineColor",
		name = "20 line colour",
		description = "Colour of the level 20 line.",
		position = 7,
		section = "multiLinesSection"
	)
	default Color twentyLineColor()
	{
		return Color.GREEN;
	}

	@ConfigItem(
		keyName = "showThirtyLine",
		name = "Show 30 line",
		description = "<html>Line between wilderness levels 30 and 31 - no teleports<br>"
			+ "work above it. Also drawn in the rev and slayer caves.</html>",
		position = 8,
		section = "multiLinesSection"
	)
	default boolean showThirtyLine()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "thirtyLineColor",
		name = "30 line colour",
		description = "Colour of the level 30 line.",
		position = 9,
		section = "multiLinesSection"
	)
	default Color thirtyLineColor()
	{
		return Color.CYAN;
	}

	// --- Teleblock Timers section ---

	@ConfigSection(
		name = "Teleblock Timers",
		description = "Countdown shown when a player - including you - gets tele-blocked.",
		position = 15,
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
		description = "Don't show teleblock timers for your own team - your configured callers and anyone in your friends chat or clan.",
		position = 4,
		section = "teleblockSection"
	)
	default boolean hideTeleblockTimersForOwnTeam()
	{
		return false;
	}

	// --- Attack Alert section ---

	@ConfigSection(
		name = "Attack Alert",
		description = "A sound and/or a flashing screen border when you're attacked or frozen.",
		position = 16,
		closedByDefault = true
	)
	String attackAlertSection = "attackAlertSection";

	@ConfigItem(
		keyName = "attackAlertSound",
		name = "Sound when attacked",
		description = "<html>Plays a sound the moment an enemy player (anyone not on<br>"
			+ "your team) starts attacking you, if nobody was already.</html>",
		position = 1,
		section = "attackAlertSection"
	)
	default boolean attackAlertSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "attackAlertGameSound",
		name = "Sound",
		description = "<html>Which sound to play - an in-game sound, or 'Custom file'<br>"
			+ "to use the .wav file set below.</html>",
		position = 2,
		section = "attackAlertSection"
	)
	default AttackAlertSound attackAlertGameSound()
	{
		return AttackAlertSound.BELL;
	}

	@ConfigItem(
		keyName = "attackAlertSoundFile",
		name = "Custom file",
		description = "<html>Full path to a .wav file, used when Sound is 'Custom file',<br>"
			+ "e.g. /home/you/alert.wav or C:\\Users\\you\\alert.wav.</html>",
		position = 3,
		section = "attackAlertSection"
	)
	default String attackAlertSoundFile()
	{
		return "";
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "attackAlertVolume",
		name = "Volume",
		description = "Volume from 0 to 100. Applies to both game sounds and custom files.",
		position = 4,
		section = "attackAlertSection"
	)
	default int attackAlertVolume()
	{
		return 80;
	}

	@ConfigItem(
		keyName = "attackAlertCooldownMillis",
		name = "Cooldown (ms)",
		description = "<html>Minimum time between two 'attacked' alerts (sound and border<br>"
			+ "flash), so being attacked on and off during a fight doesn't<br>"
			+ "repeat them over and over.</html>",
		position = 5,
		section = "attackAlertSection"
	)
	default int attackAlertCooldownMillis()
	{
		return 5000;
	}

	@ConfigItem(
		keyName = "borderFlashWhenAttacked",
		name = "Flash border when attacked",
		description = "<html>Flashes a coloured border around the whole game screen<br>"
			+ "when an enemy player starts attacking you.</html>",
		position = 6,
		section = "attackAlertSection"
	)
	default boolean borderFlashWhenAttacked()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "borderFlashAttackedColor",
		name = "Attacked colour",
		description = "Border colour when you're attacked.",
		position = 7,
		section = "attackAlertSection"
	)
	default Color borderFlashAttackedColor()
	{
		return new Color(255, 40, 40, 200);
	}

	@ConfigItem(
		keyName = "borderFlashWhenFrozen",
		name = "Flash border when frozen",
		description = "<html>Flashes a coloured border around the whole game screen<br>"
			+ "when you're hit by Bind/Snare/Entangle or an Ice spell.<br>"
			+ "Works even with Freeze Timers turned off.</html>",
		position = 8,
		section = "attackAlertSection"
	)
	default boolean borderFlashWhenFrozen()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "borderFlashFrozenColor",
		name = "Frozen colour",
		description = "Border colour when you're frozen.",
		position = 9,
		section = "attackAlertSection"
	)
	default Color borderFlashFrozenColor()
	{
		return new Color(80, 200, 255, 200);
	}

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "borderFlashWidth",
		name = "Border width",
		description = "Thickness of the flashing border, in pixels.",
		position = 10,
		section = "attackAlertSection"
	)
	default int borderFlashWidth()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "borderFlashDurationMillis",
		name = "Flash duration (ms)",
		description = "How long the border keeps flashing.",
		position = 11,
		section = "attackAlertSection"
	)
	default int borderFlashDurationMillis()
	{
		return 1500;
	}

	// --- Scout section ---

	@ConfigSection(
		name = "Scout (Discord)",
		description = "<html>Hotkey that posts a scout report - team capes seen near<br>"
			+ "you, world and location - to a Discord channel.</html>",
		position = 17,
		closedByDefault = true
	)
	String scoutSection = "scoutSection";

	@ConfigItem(
		keyName = "scoutWebhookUrl",
		name = "Discord webhook URL",
		description = "<html>The channel's webhook URL. In Discord: channel settings,<br>"
			+ "Integrations, Webhooks, New Webhook, Copy Webhook URL.<br>"
			+ "Keep it private - anyone with it can post in that channel.</html>",
		position = 1,
		section = "scoutSection",
		secret = true
	)
	default String scoutWebhookUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "scoutHotkey",
		name = "Scout hotkey",
		description = "<html>Pressing this posts e.g. '10 Team 50 (Vengy) capes, 4 other<br>"
			+ "players, W308, Ferox Enclave, lvl 15 wild' to the webhook's<br>"
			+ "channel. Your own team isn't counted.</html>",
		position = 2,
		section = "scoutSection"
	)
	default Keybind scoutHotkey()
	{
		return new Keybind(KeyEvent.VK_F9, 0);
	}

	// --- Debug section ---

	@ConfigSection(
		name = "Debug",
		description = "Diagnostic logging - only needed if something isn't working.",
		position = 18,
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
		name = "Show/edit wilderness locations",
		description = "<html>Helper for building the wilderness location list (see the<br>"
			+ "sidebar's Location Mapper tool). Draws every saved area's<br>"
			+ "outer border on your current plane, labelled with its name,<br>"
			+ "plus a live preview (different colour) while editing one in<br>"
			+ "the sidebar. Also enables shift-right-click on the ground to<br>"
			+ "set Corner A/B directly, without needing the sidebar buttons.<br>"
			+ "Turn on temporarily while mapping, then off.</html>",
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
		position = 14,
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
			+ "callers and anyone in your friends chat or clan - so AoE freezes<br>"
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
			+ "team - a configured caller, friends chat or clan member - so<br>"
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
