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

	boolean immune;
	int immunityEndTick;
	long immunityStartTimeMillis;

	/**
	 * Where the player stood last tick - moving while "frozen" means the freeze isn't real.
	 */
	WorldPoint lastLocation;

	FreezeState(int spriteId, int startTick, int endTick, long startTimeMillis, long durationMillis, WorldPoint location)
	{
		this.spriteId = spriteId;
		this.startTick = startTick;
		this.endTick = endTick;
		this.startTimeMillis = startTimeMillis;
		this.durationMillis = durationMillis;
		this.lastLocation = location;
	}
}
