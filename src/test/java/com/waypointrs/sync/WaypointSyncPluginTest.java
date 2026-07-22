package com.waypointrs.sync;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches RuneLite with Waypoint Sync loaded, for personal/dev use.
 * Run from the CLI with:  ./gradlew runClient
 * or run this main() from your IDE.
 */
public class WaypointSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(WaypointSyncPlugin.class);
		RuneLite.main(args);
	}
}
