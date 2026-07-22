package com.waypointrs.sync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("waypointsync")
public interface WaypointSyncConfig extends Config
{
	/**
	 * The only value a user supplies. There is no URL setting: everyone syncs to the hosted app,
	 * and local development uses -Dwaypoint.url instead (see WaypointSyncPlugin.baseUrl()).
	 *
	 * secret = true masks the field in the settings panel and keeps it out of RuneLite's config
	 * export and profile sync. The token is a bearer credential for one Waypoint account, so
	 * anyone holding it can overwrite that account's progress.
	 */
	@ConfigItem(
		keyName = "token",
		name = "Plugin token",
		description = "Paste the token from Waypoint: Import > Auto-sync > Generate. "
			+ "Nothing is uploaded until this is set.",
		secret = true,
		position = 1
	)
	default String token()
	{
		return "";
	}

	@ConfigItem(
		keyName = "syncOnLogin",
		name = "Sync on login",
		description = "Automatically sync quests when you log in",
		position = 3
	)
	default boolean syncOnLogin()
	{
		return true;
	}

	@ConfigItem(
		keyName = "loginDelaySeconds",
		name = "Login sync delay (sec)",
		description = "Wait this long after login before the first sync. Quest varbits are not "
			+ "loaded the instant you log in, so syncing immediately under-reports completed "
			+ "quests.",
		position = 4
	)
	default int loginDelaySeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "syncIntervalMinutes",
		name = "Re-sync every (min)",
		description = "How often to re-sync during a session (0 to disable periodic sync)",
		position = 5
	)
	default int syncIntervalMinutes()
	{
		return 5;
	}
}
