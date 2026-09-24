package net.runelite.client.plugins.preptools;

/**
 * One player's teleblock. No immunity phase and no movement check - teleblock doesn't stop
 * movement and can be recast as soon as it ends.
 */
class TeleblockState
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
