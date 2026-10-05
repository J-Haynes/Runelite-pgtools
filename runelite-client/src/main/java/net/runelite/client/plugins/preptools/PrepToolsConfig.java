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
		keyName = "frozenTargetColor",
		name = "Frozen target colour",
		description = "<html>Used instead of the target colour while the target is<br>"
			+ "frozen (not while immune). Works with Freeze Timers off.<br>"
			+ "Set it to the target colour to turn this off.</html>",
		position = 2,
		section = "targetSection"
	)
	default Color frozenTargetColor()
	{
		return new Color(0, 170, 255); // ice blue
	}

	@ConfigItem(
		keyName = "prioritiseTargetAttack",
		name = "Prioritise attack option",
		description = "<html>Forces 'Attack' to the top of the right-click menu<br>"
			+ "for the target, so they're always left-click<br>"
			+ "attackable even in a crowd.</html>",
		position = 3,
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
		position = 4,
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
		position = 5,
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
		position = 6,
		section = "targetSection"
	)
	default int targetFlashDurationMillis()
	{
		return 1500;
	}

	// --- Attackers section ---

	@ConfigSection(
		name = "Attackers",
		description = "Highlights enemy players who are attacking YOU.",
		position = 7,
		closedByDefault = true
	)
	String attackersSection = "attackersSection";

	@ConfigItem(
		keyName = "highlightAttackers",
		name = "Highlight attackers",
		description = "<html>Highlights enemy players (anyone not on your team) who<br>"
			+ "are attacking you - like Target, but for whoever is<br>"
			+ "targeting you instead of your caller's target.</html>",
		position = 1,
		section = "attackersSection"
	)
	default boolean highlightAttackers()
	{
		return false;
	}

	@ConfigItem(
		keyName = "attackerColor",
		name = "Attacker colour",
		description = "<html>Highlight colour for attackers. A player who's also a<br>"
			+ "snipe, pile hit or target shows that colour instead.</html>",
		position = 2,
		section = "attackersSection"
	)
	default Color attackerColor()
	{
		return new Color(255, 140, 0);
	}

	@ConfigItem(
		keyName = "maxAttackers",
		name = "Max highlighted",
		description = "<html>Highlight at most this many attackers - the ones who<br>"
			+ "started attacking you first. 0 = no limit.</html>",
		position = 3,
		section = "attackersSection"
	)
	default int maxAttackers()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "prioritiseAttackerAttack",
		name = "Prioritise attack option",
		description = "<html>Forces 'Attack' to the top of the right-click menu for<br>"
			+ "a highlighted attacker, so they're left-click attackable.<br>"
			+ "Tie-break against target/pile/snipe is set in the Menu<br>"
			+ "section.</html>",
		position = 4,
		section = "attackersSection"
	)
	default boolean prioritiseAttackerAttack()
	{
		return false;
	}

	// --- Highlight Others section ---
	// Built in from core Player Indicators' "Highlight others" (names of everyone not on your team),
	// plus an "only in your level range" filter.

	@ConfigSection(
		name = "Highlight Others",
		description = "<html>Names over everyone not on your team, like Player<br>"
			+ "Indicators' 'Highlight others' - optionally only those you<br>"
			+ "could attack.</html>",
		position = 8,
		closedByDefault = true
	)
	String othersSection = "othersSection";

	@ConfigItem(
		keyName = "highlightOthers",
		name = "Highlight others",
		description = "<html>Shows the name of every player not on your team (you,<br>"
			+ "friends chat, clan, callers) over their head. Turn off Player<br>"
			+ "Indicators' own 'Highlight others' if you use this.</html>",
		position = 1,
		section = "othersSection"
	)
	default boolean highlightOthers()
	{
		return false;
	}

	@ConfigItem(
		keyName = "othersColor",
		name = "Others colour",
		description = "Colour of other players' names.",
		position = 2,
		section = "othersSection"
	)
	default Color othersColor()
	{
		return Color.RED;
	}

	@ConfigItem(
		keyName = "othersOnlyInRange",
		name = "Only in your level range",
		description = "<html>Only name players you could attack: combat level within<br>"
			+ "the wilderness level (+15 on PvP worlds), with the buffer<br>"
			+ "below. In Ferox Enclave, counts from the level you're<br>"
			+ "standing in, and skips anyone inside Ferox. Outside the<br>"
			+ "wilderness (e.g. Clan Wars) everyone is shown.</html>",
		position = 3,
		section = "othersSection"
	)
	default boolean othersOnlyInRange()
	{
		return false;
	}

	@Range(min = 0, max = 30)
	@ConfigItem(
		keyName = "othersRangeBuffer",
		name = "Range buffer (levels)",
		description = "<html>Widens your level range by this many combat levels each<br>"
			+ "way - shows players a level or two from being attackable.</html>",
		position = 4,
		section = "othersSection"
	)
	default int othersRangeBuffer()
	{
		return 0;
	}

	// --- Menu section ---

	@ConfigSection(
		name = "Menu",
		description = "Right-click menu tweaks that aren't tied to callers/targets specifically.",
		position = 9,
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
		keyName = "attackPriority1",
		name = "1st Attack priority",
		description = "<html>When more than one of target, pile hit, snipe list hit and<br>"
			+ "attacker land on the same tile, whose 'Attack' wins the top of<br>"
			+ "the menu - checked 1st to 4th. Each category's own<br>"
			+ "prioritisation can still be turned off in its own section;<br>"
			+ "this only breaks the tie. A category left out (picked twice<br>"
			+ "elsewhere) goes last.</html>",
		position = 2,
		section = "menuSection"
	)
	default AttackCategory attackPriority1()
	{
		return AttackCategory.TARGET;
	}

	@ConfigItem(
		keyName = "attackPriority2",
		name = "2nd Attack priority",
		description = "See '1st Attack priority'.",
		position = 3,
		section = "menuSection"
	)
	default AttackCategory attackPriority2()
	{
		return AttackCategory.PILE;
	}

	@ConfigItem(
		keyName = "attackPriority3",
		name = "3rd Attack priority",
		description = "See '1st Attack priority'.",
		position = 4,
		section = "menuSection"
	)
	default AttackCategory attackPriority3()
	{
		return AttackCategory.SNIPE;
	}

	@ConfigItem(
		keyName = "attackPriority4",
		name = "4th Attack priority",
		description = "See '1st Attack priority'.",
		position = 5,
		section = "menuSection"
	)
	default AttackCategory attackPriority4()
	{
		return AttackCategory.ATTACKER;
	}

	@ConfigItem(
		keyName = "hideAttackOnTeammates",
		name = "Hide Attack on teammates",
		description = "<html>Removes 'Attack' from the menu when the player is your<br>"
			+ "own team - a configured caller, friends chat or clan member.<br>"
			+ "Stops a mis-click on whoever is leading the fall-in (or<br>"
			+ "anyone else stacked in your pile) from attacking them.</html>",
		position = 6,
		section = "menuSection"
	)
	default boolean hideAttackOnTeammates()
	{
		return true;
	}

	/**
	 * Key kept from when this blocked freeze spells only ("Prevent freezing teammates"), so a
	 * choice made then isn't lost.
	 */
	@ConfigItem(
		keyName = "preventFreezingTeammates",
		name = "Block spells on teammates",
		description = "<html>Removes every 'Cast (spell)' option from the menu when the<br>"
			+ "player is your own team - a configured caller, friends chat or<br>"
			+ "clan member - so you can't freeze, immunise, teleblock or hit a<br>"
			+ "teammate by mis-click. Lunar spells meant for teammates<br>"
			+ "(Vengeance Other, Heal Other, Energy Transfer, Cure Other, Stat<br>"
			+ "Spy) are still allowed. Turn off to cast on teammates.</html>",
		position = 7,
		section = "menuSection"
	)
	default boolean preventFreezingTeammates()
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
		keyName = "frozenPileColor",
		name = "Frozen pile colour",
		description = "<html>Used instead of the pile colour while that player is<br>"
			+ "frozen (not while immune). Works with Freeze Timers off.<br>"
			+ "Set it to the pile colour to turn this off.</html>",
		position = 4,
		section = "targetedPlayerSection"
	)
	default Color frozenPileColor()
	{
		return new Color(215, 140, 255); // light purple
	}

	@ConfigItem(
		keyName = "prioritiseAttackForPiles",
		name = "Prioritise attack option",
		description = "<html>Forces 'Attack' to the top of the right-click menu<br>"
			+ "for a player caught by the pile finder. The caller's<br>"
			+ "target (Target section) always wins over this if a<br>"
			+ "player happens to be both.</html>",
		position = 5,
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
		position = 10,
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
		position = 11,
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
		description = "Quickly type the name of whoever you're following/piling into public chat.",
		position = 12,
		closedByDefault = true
	)
	String pileSpamSection = "pileSpamSection";

	@ConfigItem(
		keyName = "chatSpamHotkey",
		name = "Hotkey",
		description = "<html>While following/interacting with a player, pressing this<br>"
			+ "types their name into your chatbox for public chat -<br>"
			+ "press Enter to send it.</html>",
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
		position = 13,
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
		description = "<html>Pressing this types your current wilderness location into<br>"
			+ "your chatbox, as 'Location: W318, Ferox Enclave, lvl 15 wild' -<br>"
			+ "press Enter to send it.</html>",
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
		description = "Which chat channel the location goes to.",
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
		description = "<html>A hotkey that types one of your saved messages into public<br>"
			+ "chat. Messages are added/selected from the sidebar's Spam tab.</html>",
		position = 14,
		closedByDefault = true
	)
	String spammerSection = "spammerSection";

	@ConfigItem(
		keyName = "spamHotkey",
		name = "Hotkey",
		description = "Pressing this types the selected (or a random) saved message into your chatbox for public chat - press Enter to send. At most once every 5 seconds.",
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
		position = 15,
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
		position = 17,
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
		position = 18,
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

	@ConfigItem(
		keyName = "borderStayWhileFrozen",
		name = "Keep border while frozen",
		description = "<html>With 'Flash border when frozen' on: after flashing, the<br>"
			+ "border stays on screen (solid) until your freeze ends.</html>",
		position = 9,
		section = "attackAlertSection"
	)
	default boolean borderStayWhileFrozen()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "borderFlashFrozenColor",
		name = "Frozen colour",
		description = "Border colour when you're frozen.",
		position = 10,
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
		position = 11,
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
		position = 12,
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
		position = 19,
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
		description = "<html>Pressing this posts e.g. '10x Team-50 (EoP), 3x Team-12,<br>"
			+ "W308, Ferox Enclave, lvl 15 wild' to the webhook's channel -<br>"
			+ "the same cape counts as the Team Capes overlay.</html>",
		position = 2,
		section = "scoutSection"
	)
	default Keybind scoutHotkey()
	{
		return new Keybind(KeyEvent.VK_F9, 0);
	}

	@Range(min = 1)
	@ConfigItem(
		keyName = "scoutMinCapeCount",
		name = "Minimum capes",
		description = "<html>Only report a team cape if at least this many players<br>"
			+ "near you are wearing it.</html>",
		position = 3,
		section = "scoutSection"
	)
	default int scoutMinCapeCount()
	{
		return 2;
	}

	// --- Nicknames section ---
	// Out of Experimental (2026-10-02) under a new key, nicknamesEnabled, so it's on for everyone -
	// the old showPlayerNicknames key defaulted off.

	@ConfigSection(
		name = "Nicknames",
		description = "Your own nicknames for players, TeamSpeak-style.",
		position = 20,
		closedByDefault = true
	)
	String nicknamesSection = "nicknamesSection";

	@ConfigItem(
		keyName = "nicknamesEnabled",
		name = "Player nicknames",
		description = "<html>Your own nicknames for any player (sidebar Names tab, or<br>"
			+ "'Set nickname' on a player - shift-right-click in game, or<br>"
			+ "right-click in the friends list, friends chat, clan list or<br>"
			+ "chat), shown after their name in the right-click menu. A<br>"
			+ "nickname follows a name change only for a player on your<br>"
			+ "friends or ignore list - the game tells no one else.</html>",
		position = 1,
		section = "nicknamesSection"
	)
	default boolean showPlayerNicknames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "nicknameTooltip",
		name = "Nickname on hover",
		description = "<html>Shows a player's nickname as a tooltip when you hover<br>"
			+ "them - in game, in the friends/ignore list, friends chat,<br>"
			+ "clan list or chat - like Friend Notes does.</html>",
		position = 2,
		section = "nicknamesSection"
	)
	default boolean nicknameTooltip()
	{
		return true;
	}

	@ConfigItem(
		keyName = "followFriendRenames",
		name = "Follow renames between logins",
		description = "<html>Saves your friends/ignore list per account and compares it<br>"
			+ "at the next login, so a nickname (and Friend Notes note)<br>"
			+ "follows a friend who renamed while you were away - even<br>"
			+ "twice. Unclear cases are offered in the Names tab.</html>",
		position = 3,
		section = "nicknamesSection"
	)
	default boolean followFriendRenames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useFriendNotesAsNicknames",
		name = "Friend notes as nicknames",
		description = "<html>Use your Friend Notes plugin notes as nicknames for<br>"
			+ "players who don't have one here. Those notes already<br>"
			+ "follow your friends' name changes.</html>",
		position = 4,
		section = "nicknamesSection"
	)
	default boolean useFriendNotesAsNicknames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNicknamesOverHeads",
		name = "Nicknames over heads",
		description = "Also draw nicknames over players' heads, not just in the menu.",
		position = 5,
		section = "nicknamesSection"
	)
	default boolean showNicknamesOverHeads()
	{
		return false;
	}

	@ConfigItem(
		keyName = "playerNicknameColor",
		name = "Nickname colour",
		description = "Colour of a player's nickname in the menu and over their head.",
		position = 6,
		section = "nicknamesSection"
	)
	default Color playerNicknameColor()
	{
		return new Color(255, 160, 255);
	}

	// --- Misc section ---

	@ConfigSection(
		name = "Misc",
		description = "Small interface additions.",
		position = 21,
		closedByDefault = true
	)
	String miscSection = "miscSection";

	@ConfigItem(
		keyName = "showWorldOnMapButton",
		name = "World on map button",
		description = "Shows your current world number over the minimap's world map button.",
		position = 1,
		section = "miscSection"
	)
	default boolean showWorldOnMapButton()
	{
		return true;
	}

	// --- Experimental section ---
	// New features that haven't been proven in real fights yet - all default off. Once one is
	// confirmed in game, move it into its own section (keep the key names).

	@ConfigSection(
		name = "Experimental",
		description = "<html>Features still being tested in real fights - all off by<br>"
			+ "default. Expect rough edges, and report what you see.</html>",
		position = 22,
		closedByDefault = true
	)
	String experimentalSection = "experimentalSection";

	@ConfigItem(
		keyName = "showFoodCounter",
		name = "Low food detector",
		description = "<html>Made for Clan Wars. Counts the anchovy pizza bites each<br>"
			+ "enemy eats - from the healing on their health bar when they<br>"
			+ "eat, 9 hp a bite, so potions (no heal) don't count and two<br>"
			+ "bites at once count as two - and shows it over their head,<br>"
			+ "e.g. 'Ate 14 (~7 left)', turning into 'LOW FOOD' once<br>"
			+ "they're nearly out. Food eaten at full hp can't be seen.<br>"
			+ "Reset when they die (out of the round - back next round<br>"
			+ "with full food) and for everyone when you enter the Clan<br>"
			+ "Wars portal. Shift-right-click a player 'Reset food count'<br>"
			+ "or use the reset hotkey to reset by hand.</html>",
		position = 1,
		section = "experimentalSection"
	)
	default boolean showFoodCounter()
	{
		return false;
	}

	@Range(min = 1, max = 28)
	@ConfigItem(
		keyName = "foodAssumedPizzas",
		name = "Assumed pizzas",
		description = "<html>How many anchovy pizzas an enemy is assumed to bring.<br>"
			+ "Every 2 eats seen is one pizza gone.</html>",
		position = 2,
		section = "experimentalSection"
	)
	default int foodAssumedPizzas()
	{
		return 14;
	}

	@Range(min = 0, max = 28)
	@ConfigItem(
		keyName = "foodLowPizzas",
		name = "Low at pizzas left",
		description = "Flag an enemy as low on food at this many pizzas left (or fewer).",
		position = 3,
		section = "experimentalSection"
	)
	default int foodLowPizzas()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "foodShowOnlyLow",
		name = "Only show low food",
		description = "Only label enemies once they're low, not every eat count.",
		position = 4,
		section = "experimentalSection"
	)
	default boolean foodShowOnlyLow()
	{
		return false;
	}

	@ConfigItem(
		keyName = "foodCountColor",
		name = "Eat count colour",
		description = "Colour of the eat count over an enemy's head.",
		position = 5,
		section = "experimentalSection"
	)
	default Color foodCountColor()
	{
		return new Color(255, 200, 80);
	}

	@ConfigItem(
		keyName = "foodLowColor",
		name = "Low food colour",
		description = "Colour of 'LOW FOOD' over an enemy's head.",
		position = 6,
		section = "experimentalSection"
	)
	default Color foodLowColor()
	{
		return new Color(80, 255, 80);
	}

	@ConfigItem(
		keyName = "foodResetHotkey",
		name = "Reset food counts hotkey",
		description = "<html>Resets every enemy's food count, e.g. at the start of a<br>"
			+ "round if entering the portal didn't already do it.</html>",
		position = 7,
		section = "experimentalSection"
	)
	default Keybind foodResetHotkey()
	{
		return Keybind.NOT_SET;
	}

	@Range(min = 10, max = 99)
	@ConfigItem(
		keyName = "foodEnemyMaxHp",
		name = "Enemy max HP (until measured)",
		description = "<html>The game only sends other players' health as a bar, not<br>"
			+ "hitpoints. Each enemy's max hp is measured from the hits<br>"
			+ "they take; until there's enough of those, this is assumed.</html>",
		position = 8,
		section = "experimentalSection"
	)
	default int foodEnemyMaxHp()
	{
		return 99;
	}

	@ConfigItem(
		keyName = "attackRangeFilter",
		name = "Grey out out-of-range enemies",
		description = "<html>An enemy outside the combat bracket for the wilderness<br>"
			+ "level (combat difference more than the shallower of your<br>"
			+ "two wilderness levels, +15 on PvP worlds) is drawn in the<br>"
			+ "out-of-range colour instead of their target/pile/snipe<br>"
			+ "colour, their Attack option is greyed, and they never get<br>"
			+ "a prioritised left-click Attack. Wilderness and PvP worlds<br>"
			+ "only - does nothing in Clan Wars or Ferox Enclave.</html>",
		position = 9,
		section = "experimentalSection"
	)
	default boolean attackRangeFilter()
	{
		return false;
	}

	@ConfigItem(
		keyName = "attackRangeBasis",
		name = "Range from",
		description = "<html>Your level only: greyed if YOU can't attack them.<br>"
			+ "Anyone on your team: greyed only if nobody on your team<br>"
			+ "on screen can - for a team spread over a few combat<br>"
			+ "levels, where a pile some of you can't reach is still a pile.</html>",
		position = 10,
		section = "experimentalSection"
	)
	default AttackRangeBasis attackRangeBasis()
	{
		return AttackRangeBasis.YOU;
	}

	@Range(min = 0, max = 10)
	@ConfigItem(
		keyName = "attackRangeMargin",
		name = "Range slack (levels)",
		description = "<html>Widens the bracket by this many combat levels each way, so<br>"
			+ "someone a step from being in range (the fight drifting a<br>"
			+ "wilderness level deeper) isn't greyed out yet.</html>",
		position = 11,
		section = "experimentalSection"
	)
	default int attackRangeMargin()
	{
		return 0;
	}

	@Alpha
	@ConfigItem(
		keyName = "outOfRangeColor",
		name = "Out-of-range colour",
		description = "Highlight and Attack option colour for an out-of-range enemy.",
		position = 12,
		section = "experimentalSection"
	)
	default Color outOfRangeColor()
	{
		return new Color(128, 128, 128);
	}

	@ConfigItem(
		keyName = "enablePerformanceTracker",
		name = "Team performance tracker",
		description = "<html>Records each teammate's fight numbers on the sidebar's<br>"
			+ "Stats tab, per round (each trip through the Clan Wars<br>"
			+ "portal): time on the called target (targets that died only),<br>"
			+ "switch time, attacks, damage taken, eats, deaths, on pile,<br>"
			+ "plus a fight log of every death. Only counts what happens<br>"
			+ "on your screen.</html>",
		position = 13,
		section = "experimentalSection"
	)
	default boolean enablePerformanceTracker()
	{
		return false;
	}

	@Range(min = 10, max = 600)
	@ConfigItem(
		keyName = "performanceMinTargetSeconds",
		name = "Rate after (seconds)",
		description = "<html>A teammate is only rated high/low once they've been on<br>"
			+ "screen this long while a target was called.</html>",
		position = 14,
		section = "experimentalSection"
	)
	default int performanceMinTargetSeconds()
	{
		return 30;
	}

	@Range(min = 1, max = 10)
	@ConfigItem(
		keyName = "easyDeathMaxAttackers",
		name = "Easy death: max attackers",
		description = "<html>Fight log: a teammate's death is only flagged 'easy' if no<br>"
			+ "more than this many enemies were on them in their last 10s.<br>"
			+ "Dying to a bigger pile is understandable - never flagged.</html>",
		position = 15,
		section = "experimentalSection"
	)
	default int easyDeathMaxAttackers()
	{
		return 3;
	}

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "easyDeathMinSeconds",
		name = "Easy death: min seconds",
		description = "<html>...and only if they'd been under attack at least this long -<br>"
			+ "long enough to have eaten. A quick burst isn't flagged.</html>",
		position = 16,
		section = "experimentalSection"
	)
	default int easyDeathMinSeconds()
	{
		return 6;
	}

	@Range(min = 0, max = 10)
	@ConfigItem(
		keyName = "easyDeathMaxPizzas",
		name = "Easy death: max pizzas",
		description = "<html>...and only if they ate no more than this many pizzas while<br>"
			+ "under attack. Also never flagged if they were off your<br>"
			+ "screen at any point - you didn't see it all.</html>",
		position = 17,
		section = "experimentalSection"
	)
	default int easyDeathMaxPizzas()
	{
		return 1;
	}

	// Hidden: edited only from the sidebar and the menu (see the note on the callers list).
	// One "Name=Nickname" per line.
	@ConfigItem(
		keyName = "playerNicknames",
		name = "Player nicknames",
		description = "Name=Nickname, one per line - edited from the sidebar's Names tab.",
		hidden = true
	)
	default String playerNicknames()
	{
		return "";
	}

	// --- Debug section ---

	@ConfigSection(
		name = "Debug",
		description = "Diagnostic logging - only needed if something isn't working.",
		position = 23,
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
		position = 16,
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

}
