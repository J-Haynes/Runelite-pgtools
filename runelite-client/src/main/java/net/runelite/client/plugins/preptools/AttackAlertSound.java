package net.runelite.client.plugins.preptools;

import net.runelite.api.SoundEffectID;

/**
 * The Attack Alert's sound choices - a handful of short, distinct in-game sound effects (from
 * SoundEffectID), plus a custom .wav file. See PrepToolsPlugin#playAttackAlert().
 */
public enum AttackAlertSound
{
	BELL("Bell", SoundEffectID.TOWN_CRIER_BELL_DING),
	GE_DING("Grand Exchange ding", SoundEffectID.GE_ADD_OFFER_DINGALING),
	PRAYER_DRAINED("Prayer drained", SoundEffectID.PRAYER_DEPLETE_TWINKLE),
	HIT("Hit", SoundEffectID.ATTACK_HIT),
	MAGIC_SPLASH("Magic splash", SoundEffectID.MAGIC_SPLASH_BOING),
	TELEPORT("Teleport", SoundEffectID.TELEPORT_VWOOP),
	BOOP("Boop", SoundEffectID.UI_BOOP),
	/**
	 * "Chart complete", played for charting anything in Sailing - id from the OSRS wiki's
	 * File:Charting_sound_effect.wav (not in SoundEffectID).
	 */
	SAILING_CHART("Sailing chart", 11166),
	CUSTOM_FILE("Custom file", -1);

	private final String label;
	final int soundId;

	AttackAlertSound(String label, int soundId)
	{
		this.label = label;
		this.soundId = soundId;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
