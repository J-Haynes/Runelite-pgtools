package net.runelite.client.plugins.preptools;

import net.runelite.api.coords.WorldPoint;

/**
 * One player's freeze, then their post-freeze immunity (same entry, immune = true). Game ticks are
 * authoritative for expiry; the millisecond fields only drive the overlays' smooth countdowns.
 */
class FreezeState
{
	final int spriteId;
	final int startTick;
	final int endTick;
	final long startTimeMillis;
	final long durationMillis;

	/**
	 * The first tick a new freeze can land - spell duration + 5 immunity ticks after the hit.
	 */
	final int immunityEndTick;

	boolean immune;
	long immunityStartTimeMillis;

	/**
	 * Where the player stood last tick - moving while "frozen" means the freeze isn't real.
	 */
	WorldPoint lastLocation;

	FreezeState(int spriteId, int startTick, int endTick, long startTimeMillis, long durationMillis, int immunityEndTick,
		WorldPoint location)
	{
		this.immunityEndTick = immunityEndTick;
		this.spriteId = spriteId;
		this.startTick = startTick;
		this.endTick = endTick;
		this.startTimeMillis = startTimeMillis;
		this.durationMillis = durationMillis;
		this.lastLocation = location;
	}

	/**
	 * How long the immune phase lasts once the shown countdown hits zero.
	 */
	long immunityDurationMillis()
	{
		return (immunityEndTick - endTick) * 600L;
	}
}
