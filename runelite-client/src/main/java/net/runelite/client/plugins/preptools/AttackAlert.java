package net.runelite.client.plugins.preptools;

import java.awt.Color;
import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.SoundEffectVolume;
import net.runelite.client.audio.AudioPlayer;

/**
 * The Attack Alert config section: a sound and/or a flashing screen border (drawn by
 * ScreenBorderFlashOverlay) when an enemy starts attacking you, and a border when you're frozen -
 * optionally kept on, solid, until the freeze ends.
 */
@Slf4j
class AttackAlert
{
	private static final long BORDER_FLASH_BLINK_MILLIS = 250;

	private final Client client;
	private final PrepToolsConfig config;
	private final TeamTracker team;
	private final FreezeTracker freezes;
	private final AudioPlayer audioPlayer;
	private final ScheduledExecutorService executor;

	private boolean previouslyAttacked;
	private long lastAlertMillis;

	// Written on the client thread, read by the overlay while rendering.
	private volatile Color borderFlashColor;
	private volatile long borderFlashStartMillis;
	private volatile long borderFlashEndMillis;

	@Inject
	AttackAlert(Client client, PrepToolsConfig config, TeamTracker team, FreezeTracker freezes,
		AudioPlayer audioPlayer, ScheduledExecutorService executor)
	{
		this.client = client;
		this.config = config;
		this.team = team;
		this.freezes = freezes;
		this.audioPlayer = audioPlayer;
		this.executor = executor;
	}

	/**
	 * Fires the "attacked" alert when an enemy (non-team) player starts interacting with you and none
	 * were the tick before. Teammates are ignored - they interact with you just by following.
	 * Rate-limited by attackAlertCooldownMillis.
	 */
	void onGameTick()
	{
		boolean sound = config.attackAlertSound();
		boolean flash = config.borderFlashWhenAttacked();
		Player local = client.getLocalPlayer();
		boolean attacked = false;
		if ((sound || flash) && local != null)
		{
			for (Player player : client.getPlayers())
			{
				if (player != null && player != local && player.getInteracting() == local && !team.isOwnTeam(player))
				{
					attacked = true;
					break;
				}
			}
		}

		long now = System.currentTimeMillis();
		if (attacked && !previouslyAttacked && now - lastAlertMillis >= Math.max(0, config.attackAlertCooldownMillis()))
		{
			lastAlertMillis = now;
			if (sound)
			{
				playSound();
			}
			if (flash)
			{
				startBorderFlash(config.borderFlashAttackedColor());
			}
		}
		previouslyAttacked = attacked;
	}

	void onLocalFrozen()
	{
		if (config.borderFlashWhenFrozen())
		{
			startBorderFlash(config.borderFlashFrozenColor());
		}
	}

	/**
	 * The border colour to draw this frame, or null for none: blinking while a flash runs, then
	 * (with "Keep border while frozen") solid until your freeze ends.
	 */
	Color getBorderColorNow()
	{
		long now = System.currentTimeMillis();
		if (now < borderFlashEndMillis)
		{
			return ((now - borderFlashStartMillis) / BORDER_FLASH_BLINK_MILLIS) % 2 == 0 ? borderFlashColor : null;
		}
		if (config.borderFlashWhenFrozen() && config.borderStayWhileFrozen() && freezes.isLocalFrozen())
		{
			return config.borderFlashFrozenColor();
		}
		return null;
	}

	void clear()
	{
		previouslyAttacked = false;
		lastAlertMillis = 0;
		borderFlashEndMillis = 0;
	}

	private void startBorderFlash(Color color)
	{
		borderFlashColor = color;
		borderFlashStartMillis = System.currentTimeMillis();
		borderFlashEndMillis = borderFlashStartMillis + Math.max(0, config.borderFlashDurationMillis());
	}

	private void playSound()
	{
		int volume = Math.max(0, Math.min(100, config.attackAlertVolume()));
		if (volume == 0)
		{
			return;
		}

		AttackAlertSound choice = config.attackAlertGameSound();
		if (choice != AttackAlertSound.CUSTOM_FILE)
		{
			// Volume is 0-127 and plays even with the game's sound effects muted.
			client.playSoundEffect(choice.soundId, volume * SoundEffectVolume.HIGH / 100);
			return;
		}

		String path = config.attackAlertSoundFile().trim();
		if (path.isEmpty())
		{
			return;
		}

		// Off the client thread: loading/decoding a file can take a moment. Gain is in dB.
		float gain = (float) (20 * Math.log10(volume / 100.0));
		executor.execute(() ->
		{
			try
			{
				audioPlayer.play(new File(path), gain);
			}
			catch (Exception e)
			{
				log.warn("PvP Tools: couldn't play attack alert sound file '{}': {}", path, e.getMessage());
			}
		});
	}
}
