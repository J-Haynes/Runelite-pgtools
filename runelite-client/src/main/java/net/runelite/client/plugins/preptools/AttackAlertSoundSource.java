package net.runelite.client.plugins.preptools;

/**
 * Where the "attacked" alert sound comes from - see PrepToolsPlugin#playAttackAlert().
 */
public enum AttackAlertSoundSource
{
	GAME_SOUND("Game sound"),
	CUSTOM_FILE("Custom file");

	private final String label;

	AttackAlertSoundSource(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
