package net.runelite.client.plugins.preptools;

import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Skill;
import net.runelite.api.kit.KitType;
import lombok.extern.slf4j.Slf4j;

/**
 * The infobox's (FightInfoOverlay) numbers, recomputed every game tick: enemy overhead prayers and
 * weapon styles on screen, the weapon styles of enemies attacking you (plus the "someone new
 * engaged you" flash), and whether your Strength/Prayer are boosted. Client thread only.
 */
@Slf4j
class FightInfoTracker
{
	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;

	/**
	 * Whether any enemy (non-team player) is on screen.
	 */
	@Getter
	private boolean anyOpponents;

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
	@Getter
	private int opponentMagicWeaponCount;

	/**
	 * Enemies attacking you, by the style of their weapon.
	 */
	@Getter
	private int meleeAttackerCount;
	@Getter
	private int rangedAttackerCount;
	@Getter
	private int magicAttackerCount;

	private boolean previouslyAttacked;
	private long attackerFlashEndTime;

	/**
	 * Strength boost above strengthGreenThreshold.
	 */
	@Getter
	private boolean strengthBoosted;

	/**
	 * Prayer points above your real Prayer level.
	 */
	@Getter
	private boolean prayerBoosted;

	@Inject
	FightInfoTracker(Client client, PrepToolsConfig config, TeamTracker team)
	{
		this.client = client;
		this.config = config;
		this.team = team;
	}

	static WeaponStyle weaponStyle(Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		return composition == null ? WeaponStyle.UNKNOWN : WeaponStyle.of(composition.getEquipmentId(KitType.WEAPON));
	}

	void onGameTick()
	{
		updateOpponents();
		updateAttackingYou();

		strengthBoosted = client.getBoostedSkillLevel(Skill.STRENGTH) - client.getRealSkillLevel(Skill.STRENGTH)
			> config.strengthGreenThreshold();
		prayerBoosted = client.getBoostedSkillLevel(Skill.PRAYER) > client.getRealSkillLevel(Skill.PRAYER);
	}

	/**
	 * Whether the "Attacking you" row is in its "someone new engaged you" flash window.
	 */
	boolean isFlashingAttacker()
	{
		return config.showAttackerFlash() && System.currentTimeMillis() < attackerFlashEndTime;
	}

	void clear()
	{
		anyOpponents = false;
		opponentMeleePrayerCount = opponentRangedPrayerCount = opponentMagicPrayerCount = 0;
		opponentMeleeWeaponCount = opponentRangedWeaponCount = opponentMagicWeaponCount = 0;
		meleeAttackerCount = rangedAttackerCount = magicAttackerCount = 0;
		previouslyAttacked = false;
		attackerFlashEndTime = 0;
		strengthBoosted = false;
		prayerBoosted = false;
	}

	private void updateOpponents()
	{
		anyOpponents = false;
		opponentMeleePrayerCount = opponentRangedPrayerCount = opponentMagicPrayerCount = 0;
		opponentMeleeWeaponCount = opponentRangedWeaponCount = opponentMagicWeaponCount = 0;

		if (!config.showFightInfoOverlay() || (!config.showOpponentPrayers() && !config.showOpponentWeapons()))
		{
			return;
		}

		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null || team.isOwnTeam(player))
			{
				continue;
			}
			anyOpponents = true;

			HeadIcon headIcon = player.getOverheadIcon();
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

			switch (weaponStyle(player))
			{
				case MELEE:
					opponentMeleeWeaponCount++;
					break;
				case RANGED:
					opponentRangedWeaponCount++;
					break;
				case MAGIC:
					opponentMagicWeaponCount++;
					break;
				default:
					break;
			}
		}
	}

	/**
	 * Enemies interacting with you - teammates are skipped, as they "interact" with you just by
	 * following you.
	 */
	private void updateAttackingYou()
	{
		meleeAttackerCount = rangedAttackerCount = magicAttackerCount = 0;

		Player local = client.getLocalPlayer();
		if (!config.showFightInfoOverlay() || !config.showAttackingYou() || local == null)
		{
			previouslyAttacked = false;
			return;
		}

		boolean debug = config.debugLogging();
		boolean attacked = false;
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local || player.getInteracting() != local || team.isOwnTeam(player))
			{
				continue;
			}
			attacked = true;

			WeaponStyle style = weaponStyle(player);
			if (debug)
			{
				log.info("PvP Tools debug: '{}' is attacking you with a {} weapon", player.getName(), style);
			}
			switch (style)
			{
				case MELEE:
					meleeAttackerCount++;
					break;
				case RANGED:
					rangedAttackerCount++;
					break;
				case MAGIC:
					magicAttackerCount++;
					break;
				default:
					break;
			}
		}

		if (attacked && !previouslyAttacked)
		{
			attackerFlashEndTime = System.currentTimeMillis() + Math.max(0, config.attackerFlashDurationMillis());
		}
		previouslyAttacked = attacked;
	}
}
