package com.waypointrs.sync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Pushes account progression to waypointosrs.com.
 *
 * Sent on login and every few minutes after, and only while a token is configured: display name,
 * quest states, achievement diary and combat achievement varbits, skill levels and xp, collection
 * log counts and item names.
 *
 * Not sent: chat, bank, inventory, position, or anything about other players. The chat subscriber
 * below matches one game message ("New item added to your collection log") and ignores the rest.
 *
 * The endpoint is fixed at {@link #DEFAULT_URL}. With no token set, nothing is read and no
 * request is made.
 */
@Slf4j
@PluginDescriptor(
	name = "Waypoint Sync",
	description = "Uploads your quests, diaries, combat achievements, skills and collection log "
		+ "to your waypointosrs.com account. Requires an account; does nothing without a token.",
	tags = {"quest", "diary", "achievement", "tracker", "progress", "waypoint", "collection log"}
)
public class WaypointSyncPlugin extends Plugin
{
	/** Not a config item: there is one hosted instance, so there is nothing for a user to set. */
	private static final String DEFAULT_URL = "https://waypointosrs.com";

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private WaypointSyncConfig config;
	@Inject private OkHttpClient httpClient;
	@Inject private Gson gson;
	@Inject private ScheduledExecutorService executor;

	/**
	 * Quest varbits stream in over several seconds after LOGGED_IN rather than being ready at
	 * once. Ingest is authoritative, so sending a half-loaded read clears quests that are actually
	 * finished. A fixed delay alone is a guess, so a read must also be stable: two consecutive
	 * reads have to agree before anything is sent.
	 */
	private static final int CONFIRM_DELAY_SECONDS = 5;

	private ScheduledFuture<?> periodic;
	private String lastQuestsJson;      // change-detection: avoid redundant posts
	private String pendingQuestsJson;   // previous read, awaiting confirmation

	/** Collection log pages seen this session: page title -> {obtained,total}. */
	private final Map<String, JsonObject> collectionPages = new LinkedHashMap<>();

	/** Combat achievement task name -> the filter value in force when it was seen. */
	private final Map<String, Integer> caSeen = new LinkedHashMap<>();
	private int caTickCounter;
	private String lastGroupsSig;
	private String lastScreenSig;
	private final java.util.Set<String> warnedGroups = new java.util.HashSet<>();
	private boolean syncedThisSession;

	/** Overview groups: Bosses/Raids/Clues/Minigames/Other -> {obtained,total}. */
	private final Map<String, JsonObject> collectionGroups = new LinkedHashMap<>();

	/** Item name -> raw widget opacity, or -1 when the game announced it in chat. */
	private final Map<String, Integer> collectionItems = new LinkedHashMap<>();

	@Provides
	WaypointSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WaypointSyncConfig.class);
	}

	@Override
	protected void startUp()
	{
		lastQuestsJson = null;
		pendingQuestsJson = null;
		collectionPages.clear();
		collectionGroups.clear();
		collectionItems.clear();
		caSeen.clear();
		syncedThisSession = false;
		caTickCounter = 0;
		// Log whether a token is present, never the token itself.
		log.debug("Waypoint Sync started (token {})",
			config.token() == null || config.token().isEmpty() ? "missing" : "set");
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// Already logged in when the plugin started, so the client is settled: no delay.
			scheduleSync(0);
		}
		int mins = config.syncIntervalMinutes();
		if (mins > 0)
		{
			periodic = executor.scheduleWithFixedDelay(() -> {
				if (client.getGameState() == GameState.LOGGED_IN)
				{
					scheduleSync(0);
				}
			}, mins, mins, TimeUnit.MINUTES);
		}
	}

	@Override
	protected void shutDown()
	{
		if (periodic != null)
		{
			periodic.cancel(true);
			periodic = null;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			syncedThisSession = false;   // re-arm for the next login
			caTickCounter = 0;
		}
		if (event.getGameState() == GameState.LOGGED_IN && config.syncOnLogin())
		{
			pendingQuestsJson = null;   // a fresh login invalidates any half-read snapshot
			syncedThisSession = true;   // event path worked, no tick fallback needed
			scheduleSync(Math.max(0, config.loginDelaySeconds()));
		}
	}

	/**
	 * Capture a collection log page when one is opened.
	 *
	 * Per-item state is not in varbits (56 of ~1500 items have dedicated ones, plus 124 category
	 * flags), so the counts only exist in the interface, and only for a page the client has drawn.
	 * Whatever page is opened gets scraped and kept server-side.
	 *
	 * Matches on the "Obtained: x/y" label rather than widget child ids, which shift between game
	 * updates.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.COLLECTION_DRAW_LIST)
		{
			return;
		}
		try
		{
			captureCollectionPage();
			captureCollectionItems();
		}
		catch (Exception ex)
		{
			log.warn("Waypoint: collection log capture failed", ex);
		}
	}

	/** Interfaces are small; scanning past the end just yields nulls. */
	private static final int MAX_INTERFACE_CHILDREN = 80;

	/** Tab order as the game indexes them in COLLECTION_LAST_TAB. */
	private static final String[] TAB_NAMES = { "Bosses", "Raids", "Clues", "Minigames", "Other" };

	/** The five top-level tabs on the collection log overview. */
	private static final java.util.Set<String> GROUPS = new java.util.HashSet<>(
		java.util.Arrays.asList("Bosses", "Raids", "Clues", "Minigames", "Other"));
	/** A bare "7/336" cell. */
	private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

	private static final Pattern OBTAINED = Pattern.compile("Obtained:\\s*(\\d+)\\s*/\\s*(\\d+)");
	/** The window header, e.g. "Collection Log - 61/1706". Whole-log progress, not a page. */
	private static final Pattern HEADER = Pattern.compile("Collection Log\\s*-\\s*(\\d+)\\s*/\\s*(\\d+)");

	/**
	 * Combat achievement tasks, read from the Tasks interface.
	 *
	 * Per-task completion is not readable as a varbit: the named CA_TASK_*_COMPLETED entries cover
	 * about 63% of tasks, and the 640-bit varplayer bitmap has no published task to bit mapping.
	 * The interface renders every task by name and the active filter is exposed as a varbit, so
	 * reading the screen is exact.
	 *
	 * Polled on a tick while the interface is open, since scrolling reveals more rows without
	 * firing a load event. Names accumulate across scrolls.
	 */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		try
		{
			gameTick();
		}
		catch (Throwable t)
		{
			if (warnedGroups.add("tick"))
			{
				log.warn("Waypoint: tick handler failed ({})", t.getClass().getSimpleName(), t);
			}
		}
	}

	private void gameTick()
	{
		caTickCounter++;

		// Fallback for the login sync. GameStateChanged is dispatched to subscribers in sequence
		// and an exception in one aborts the rest, so an unrelated plugin throwing in onLogin can
		// stop LOGGED_IN reaching this one. Ticks are not affected by that.
		if (!syncedThisSession && client.getGameState() == GameState.LOGGED_IN
			&& client.getLocalPlayer() != null
			&& caTickCounter > Math.max(1, config.loginDelaySeconds()) / 2)
		{
			syncedThisSession = true;
			log.debug("Waypoint: login sync (tick fallback)");
			scheduleSync(0);
		}

		if (caTickCounter % 2 != 0)
		{
			return;   // every other tick is enough, the list only changes on scroll
		}

		// The overview does not fire COLLECTION_DRAW_LIST, so poll for it here. Without its
		// per-group totals the server has to sum pages instead, which double-counts drops shared
		// between logs.
		if (interfaceOpen(InterfaceID.COLLECTION) || interfaceOpen(InterfaceID.COLLECTION_OVERVIEW))
		{
			try
			{
				captureCollectionPage();
				captureCollectionItems();
			}
			catch (Exception ex)
			{
				log.debug("Waypoint: collection poll failed", ex);
			}
		}
		// Probe a range of children rather than gating on child 0: an interface's root child is
		// not necessarily 0.
		if (!interfaceOpen(InterfaceID.CA_TASKS))
		{
			if (caTickCounter % 20 == 0 && interfaceOpen(InterfaceID.CA_OVERVIEW))
			{
				log.debug("Waypoint: CA overview is open but the Tasks list is not. "
					+ "Open the Tasks tab and set Completed=Complete to record finished tasks.");
			}
			return;
		}
		try
		{
			captureCombatTasks();
		}
		catch (Exception ex)
		{
			log.debug("Waypoint: combat task capture failed", ex);
		}
	}

	/** True if any child of the group is loaded. Interfaces do not reliably use child 0. */
	private boolean interfaceOpen(int group)
	{
		for (int child = 0; child < MAX_INTERFACE_CHILDREN; child++)
		{
			if (client.getWidget(group, child) != null)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The 20 varps holding the per-task completion bitmap. Listed explicitly because the ids are
	 * not consecutive: 0..12 run 3116-3128, then 3387, 3718, 3773, 3774, 4204, 4496, 4721.
	 * Reading them as base+i picks up bits from unrelated varps.
	 */
	private static final int[] CA_TASK_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0,  VarPlayerID.CA_TASK_COMPLETED_1,
		VarPlayerID.CA_TASK_COMPLETED_2,  VarPlayerID.CA_TASK_COMPLETED_3,
		VarPlayerID.CA_TASK_COMPLETED_4,  VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6,  VarPlayerID.CA_TASK_COMPLETED_7,
		VarPlayerID.CA_TASK_COMPLETED_8,  VarPlayerID.CA_TASK_COMPLETED_9,
		VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13,
		VarPlayerID.CA_TASK_COMPLETED_14, VarPlayerID.CA_TASK_COMPLETED_15,
		VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19,
	};

	/**
	 * Number of completed combat achievement tasks. Readable at login without opening anything.
	 *
	 * Which task each bit refers to would need a task to bit-index mapping from the cache, which
	 * is not exposed, so only the total is used. The server compares it against the number of task
	 * names captured so far to tell when its per-task list is stale.
	 */
	private int completedTaskCount()
	{
		int set = 0;
		for (int varp : CA_TASK_VARPS)
		{
			set += Integer.bitCount(client.getVarpValue(varp));
		}
		return set;
	}

	private void captureCombatTasks()
	{
		// The "Completed" dropdown, 0/1/2. Recorded raw and interpreted server-side.
		final int filter = client.getVarbitValue(VarbitID.CA_TASK_FILTER_COMPLETED);

		final java.util.List<String> texts = new java.util.ArrayList<>();
		for (int child = 0; child < MAX_INTERFACE_CHILDREN; child++)
		{
			collectText(client.getWidget(InterfaceID.CA_TASKS, child), texts, 0);
		}
		if (texts.isEmpty())
		{
			if (caTickCounter % 20 == 0)
			{
				log.debug("Waypoint: CA Tasks interface open but no text read yet.");
			}
			return;
		}

		// No attempt to infer the row structure. The server holds all 637 task names already, so
		// every plausible string is sent and matched there. That survives a relayout of the
		// interface, which positional parsing would not.
		int added = 0;
		for (final String t : texts)
		{
			final String name = t.trim();
			if (name.isEmpty() || name.length() > 60
				|| name.startsWith("Monster:") || name.endsWith(":"))
			{
				continue;
			}
			if (caSeen.putIfAbsent(name, filter) == null)
			{
				added++;
			}
		}
		if (added > 0)
		{
			log.debug("Waypoint: captured {} combat task name(s), filter={} (total {})",
				added, filter, caSeen.size());
			scheduleSync(0);
		}
		else if (caTickCounter % 40 == 0)
		{
			log.debug("Waypoint: CA Tasks open, {} strings on screen, {} names held, filter={}",
				texts.size(), caSeen.size(), filter);
		}
	}

	/** Depth-first, document order, colour tags stripped. Depth-guarded against cycles. */
	private void collectText(Widget w, java.util.List<String> out, int depth)
	{
		if (w == null || depth > 8 || out.size() > 400)
		{
			return;
		}
		final String raw = w.getText();
		if (raw != null && !raw.isEmpty())
		{
			final String text = raw.replaceAll("<[^>]*>", "").trim();
			if (!text.isEmpty())
			{
				out.add(text);
			}
		}
		for (Widget[] kids : new Widget[][]{ w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren() })
		{
			if (kids != null)
			{
				for (Widget k : kids)
				{
					collectText(k, out, depth + 1);
				}
			}
		}
	}

	/**
	 * Collection log item tracking, from two sources of differing confidence.
	 *
	 * The game announces each new entry in chat ("New item added to your collection log: Abyssal
	 * whip"). That is unambiguous and arrives as it happens, so it keeps the list current without
	 * anything being opened. Opening a log page renders its items with unobtained ones dimmed,
	 * which backfills everything obtained before the plugin was installed.
	 *
	 * Opacity is sent raw and interpreted server-side, since its exact meaning is an assumption.
	 */
	private static final Pattern COLLOG_CHAT =
		Pattern.compile("New item added to your collection log:\\s*(.+?)\\s*$");

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		final String msg = event.getMessage().replaceAll("<[^>]*>", "").trim();
		final Matcher m = COLLOG_CHAT.matcher(msg);
		if (!m.find())
		{
			return;
		}
		final String item = m.group(1).trim();
		if (item.isEmpty())
		{
			return;
		}
		// -1 marks "confirmed by the game itself", distinct from any opacity value.
		collectionItems.put(item, -1);
		log.debug("Waypoint: collection log gain '{}' (now holding {} item states)",
			item, collectionItems.size());
		scheduleSync(0);
	}

	/** Read the item grid of whichever collection log page is open. */
	private void captureCollectionItems()
	{
		int added = 0;
		for (int child = 0; child < MAX_INTERFACE_CHILDREN; child++)
		{
			final Widget w = client.getWidget(InterfaceID.COLLECTION, child);
			added += collectItemWidgets(w, 0);
		}
		if (added > 0)
		{
			log.debug("Waypoint: read {} collection item(s) from the open page ({} held)",
				added, collectionItems.size());
		}
	}

	private int collectItemWidgets(Widget w, int depth)
	{
		if (w == null || depth > 8)
		{
			return 0;
		}
		int added = 0;
		final int itemId = w.getItemId();
		if (itemId > 0)
		{
			try
			{
				final String name = client.getItemDefinition(itemId).getName();
				if (name != null && !name.isEmpty() && !"null".equals(name))
				{
					// Never downgrade a chat-confirmed entry (-1) to an opacity reading.
					final Integer prev = collectionItems.get(name);
					if (prev == null || prev != -1)
					{
						if (collectionItems.put(name, w.getOpacity()) == null)
						{
							added++;
						}
					}
				}
			}
			catch (Exception ignored)
			{
			}
		}
		for (Widget[] kids : new Widget[][]{ w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren() })
		{
			if (kids != null)
			{
				for (Widget k : kids)
				{
					added += collectItemWidgets(k, depth + 1);
				}
			}
		}
		return added;
	}

	private void captureCollectionPage()
	{
		// Scan the group's children, not just child 0. The page heading, category list and
		// "Obtained:" line are siblings of child 0 rather than descendants of it.
		//
		// Text is collected in document order because the heading renders immediately above
		// "Obtained: x/y" and is taken as the nearest preceding label. A stack-based walk reverses
		// that adjacency and picks up decoration instead.
		final java.util.List<String> seenText = new java.util.ArrayList<>();
		for (int group : new int[]{ InterfaceID.COLLECTION, InterfaceID.COLLECTION_OVERVIEW })
		{
			for (int child = 0; child < MAX_INTERFACE_CHILDREN; child++)
			{
				collectText(client.getWidget(group, child), seenText, 0);
			}
		}
		if (seenText.isEmpty())
		{
			return;
		}

		String title = null;
		int obtained = -1, total = -1;
		int overallObtained = -1, overallTotal = -1;
		int obtainedAt = -1;

		for (int i = 0; i < seenText.size(); i++)
		{
			final String text = seenText.get(i);
			final Matcher m = OBTAINED.matcher(text);
			if (m.find() && obtainedAt < 0)
			{
				obtained = Integer.parseInt(m.group(1));
				total = Integer.parseInt(m.group(2));
				obtainedAt = i;
				continue;
			}
			final Matcher h = HEADER.matcher(text);
			if (h.find())
			{
				overallObtained = Integer.parseInt(h.group(1));
				overallTotal = Integer.parseInt(h.group(2));
			}
		}

		// The overview lists the five top-level groups with their totals, laid out as all five
		// labels followed by all five counts, not as label/count pairs. Scanning forward from a
		// label for a nearby count therefore mis-assigns them. Collect labels and counts
		// separately in document order and pair them by position.
		final java.util.List<String> groupLabels = new java.util.ArrayList<>();
		final java.util.List<int[]> groupCounts = new java.util.ArrayList<>();
		for (final String text : seenText)
		{
			if (GROUPS.contains(text) && !groupLabels.contains(text))
			{
				groupLabels.add(text);
				continue;
			}
			if (HEADER.matcher(text).find())
			{
				continue;   // "Collection Log - 61/1706" is the window title, not a group
			}
			final Matcher cm = COUNT.matcher(text);
			if (cm.matches())
			{
				groupCounts.add(new int[]{ Integer.parseInt(cm.group(1)), Integer.parseInt(cm.group(2)) });
			}
		}
		// Only accept it when the shape is right: five labels and at least five counts.
		if (groupLabels.size() == GROUPS.size() && groupCounts.size() >= groupLabels.size())
		{
			for (int i = 0; i < groupLabels.size(); i++)
			{
				final JsonObject grp = new JsonObject();
				grp.addProperty("obtained", groupCounts.get(i)[0]);
				grp.addProperty("total", groupCounts.get(i)[1]);
				collectionGroups.put(groupLabels.get(i), grp);
			}
		}

		// Walk back from the count to the page heading, skipping decoration.
		for (int i = obtainedAt - 1; i >= 0 && title == null; i--)
		{
			final String t = seenText.get(i);
			if (t.length() < 3 || t.length() > 40) continue;   // '*', scrollbar glyphs, prose
			if (t.indexOf(':') >= 0) continue;                 // "Personal Best: 9:25" etc.
			if (HEADER.matcher(t).find()) continue;            // the window header
			if (t.equalsIgnoreCase("Search")) continue;        // the search button
			title = t;
		}

		if (collectionGroups.isEmpty())
		{
			// Report which screen is open and what is on it, keyed so it logs once per screen.
			final String sig = "screen:" + seenText.size() + ":" + (seenText.isEmpty() ? "" : seenText.get(0));
			if (!sig.equals(lastScreenSig))
			{
				lastScreenSig = sig;
				log.debug("Waypoint: collection screen open (COLLECTION={}, OVERVIEW={}), no group "
						+ "totals found. First 25 strings: {}",
					interfaceOpen(InterfaceID.COLLECTION), interfaceOpen(InterfaceID.COLLECTION_OVERVIEW),
					seenText.size() > 25 ? seenText.subList(0, 25) : seenText);
			}
		}

		if (!collectionGroups.isEmpty())
		{
			final String sig = collectionGroups.toString();
			if (!sig.equals(lastGroupsSig))
			{
				lastGroupsSig = sig;
				log.debug("Waypoint: captured collection overview {}", collectionGroups.keySet());
				scheduleSync(0);
			}
		}

		if (title == null || title.isEmpty() || obtained < 0)
		{
			// Log the failure rather than returning silently, so a bad parse is distinguishable
			// from the event never firing.
			log.debug("Waypoint: collection page unreadable (title={}, obtained={}, overall={}/{}). "
					+ "Text widgets found: {}",
				title, obtained, overallObtained, overallTotal,
				seenText.size() > 60 ? seenText.subList(0, 60) : seenText);
			return;
		}

		final JsonObject page = new JsonObject();
		page.addProperty("obtained", obtained);
		page.addProperty("total", total);
		// Which of the five tabs this page belongs to, taken from COLLECTION_LAST_TAB rather than
		// inferred from the title. Matching titles against the flag keys misses most raids.
		final int tab = client.getVarbitValue(VarbitID.COLLECTION_LAST_TAB);
		if (tab >= 0 && tab < TAB_NAMES.length)
		{
			page.addProperty("group", TAB_NAMES[tab]);
		}
		final JsonObject prev = collectionPages.put(title, page);
		if (prev != null && prev.toString().equals(page.toString()))
		{
			return;   // identical to what is already held, nothing to send
		}
		log.debug("Waypoint: captured collection page '{}' {}/{}", title, obtained, total);
		scheduleSync(0);
	}

	/** Reading quest state touches varbits, so it must run on the client thread. */
	private void scheduleSync(int delaySeconds)
	{
		if (delaySeconds <= 0)
		{
			clientThread.invokeLater(this::syncNow);
		}
		else
		{
			executor.schedule(() -> clientThread.invokeLater(this::syncNow),
				delaySeconds, TimeUnit.SECONDS);
		}
	}

	/**
	 * Read every constant on {@code source} whose name starts with {@code prefix} and optionally
	 * ends with {@code suffix}, returning {suffix: value}.
	 *
	 * A name scan over public constants rather than 48 hand-written lines, so a new diary area
	 * picks itself up instead of going quietly missing. VarbitID is plain API, not obfuscated,
	 * so the names are stable.
	 */
	private JsonObject readVarbitGroup(Class<?> source, String prefix, String suffix)
	{
		final JsonObject out = new JsonObject();
		for (Field f : source.getDeclaredFields())
		{
			if (!Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith(prefix)
				|| (suffix != null && !f.getName().endsWith(suffix)))
			{
				continue;
			}
			try
			{
				out.addProperty(f.getName().substring(prefix.length()), client.getVarbitValue(f.getInt(null)));
			}
			catch (Exception ex)
			{
				// A constant that isn't an int, or a varbit this revision doesn't know: skip it.
			}
		}
		return out;
	}

	private int safeInt(java.util.function.IntSupplier read)
	{
		try
		{
			return read.getAsInt();
		}
		catch (Throwable t)
		{
			return -1;
		}
	}

	/**
	 * Read one group of varbits, isolating any failure to that group.
	 *
	 * net.runelite.api.gameval is compiled against here, but RuneLite loads net.runelite.api from
	 * the injected client it downloads at startup, which can shadow it. On a client predating
	 * gameval these throw NoClassDefFoundError at runtime, so losing diaries must not also lose
	 * quests.
	 */
	private JsonObject safeGroup(String what, java.util.function.Supplier<JsonObject> read)
	{
		try
		{
			return read.get();
		}
		catch (Throwable t)
		{
			if (warnedGroups.add(what))
			{
				log.warn("Waypoint: {} unavailable on this client ({}), skipping that group",
					what, t.getClass().getSimpleName());
			}
			return new JsonObject();
		}
	}

	/** The six combat achievement tiers, in game order. Unlike diaries, this set does not grow. */
	private static final String[] CA_TIERS =
		{ "EASY", "MEDIUM", "HARD", "ELITE", "MASTER", "GRANDMASTER" };

	/**
	 * Tier completion status and tasks completed per tier.
	 *
	 * Listed explicitly instead of scanned by prefix the way diaries are, because
	 * CA_TOTAL_TASKS_COMPLETED_ also matches dozens of per-boss counters (_COLOSSEUM, _ARAXXOR,
	 * _THEATREOFBLOOD_HARD and so on) that would end up in the payload as junk keys.
	 */
	private static final int[] CA_TIER_STATUS = {
		VarbitID.CA_TIER_STATUS_EASY, VarbitID.CA_TIER_STATUS_MEDIUM,
		VarbitID.CA_TIER_STATUS_HARD, VarbitID.CA_TIER_STATUS_ELITE,
		VarbitID.CA_TIER_STATUS_MASTER, VarbitID.CA_TIER_STATUS_GRANDMASTER,
	};

	private static final int[] CA_TIER_COUNT = {
		VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY, VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD, VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER, VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER,
	};

	private JsonObject readTiers(int[] varbits)
	{
		final JsonObject out = new JsonObject();
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			out.addProperty(CA_TIERS[i], client.getVarbitValue(varbits[i]));
		}
		return out;
	}

	/**
	 * Achievement diary completion, keyed AREA_TIER to match the server's diaryKey.
	 *
	 * Uses the VarbitID <AREA>_DIARY_<TIER>_COMPLETE namespace rather than the older
	 * Varbits.DIARY_<AREA>_<TIER> set. The legacy Karamja varbits (3578/3599/3611) are task
	 * counters, not completion flags: they read 1 after a single task, which reports all three
	 * Karamja tiers complete for an account that has barely started them. The newer namespace has
	 * no Karamja EASY/MEDIUM/HARD entry at all, only ELITE, so those three are absent here and the
	 * server leaves them alone. Their real progress is sent in diaryCounts instead.
	 */
	private JsonObject readDiaries()
	{
		final JsonObject out = new JsonObject();
		for (Field f : VarbitID.class.getDeclaredFields())
		{
			final String n = f.getName();
			if (!Modifier.isStatic(f.getModifiers()) || !n.contains("_DIARY_") || !n.endsWith("_COMPLETE"))
			{
				continue;
			}
			if (n.startsWith("WILDERNESS_DIARY_ANY"))
			{
				continue; // "any tier done" roll-up, not a tier of its own
			}
			try
			{
				final String key = n.replace("_DIARY_", "_").replace("_COMPLETE", "");
				out.addProperty(key, client.getVarbitValue(f.getInt(null)));
			}
			catch (Exception ignored)
			{
			}
		}
		return out;
	}

	/**
	 * All 24 skills with real (unboosted) level and exact xp.
	 *
	 * Saves the server an outbound hiscores lookup per user, and is fresher than the hiscores,
	 * which lag behind actual play.
	 */
	private JsonObject readSkills()
	{
		final JsonObject out = new JsonObject();
		for (Skill skill : Skill.values())
		{
			try
			{
				final JsonObject s = new JsonObject();
				s.addProperty("level", client.getRealSkillLevel(skill));
				s.addProperty("xp", client.getSkillExperience(skill));
				out.add(skill.getName(), s);
			}
			catch (Exception ignored)
			{
				// A skill this revision doesn't know: skip rather than send a zero.
			}
		}
		return out;
	}

	private void syncNow()
	{

		// getLocalPlayer() stays null briefly after LOGGED_IN, and quest varbits are not ready
		// while it is.
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			pendingQuestsJson = null;
			return;
		}
		if (config.token() == null || config.token().isEmpty())
		{
			log.debug("Waypoint: no token configured, skipping");
			return;
		}

		final JsonObject quests = new JsonObject();
		for (Quest quest : Quest.values())
		{
			try
			{
				QuestState state = quest.getState(client);
				quests.addProperty(quest.getName(), state.name()); // FINISHED / IN_PROGRESS / NOT_STARTED
			}
			catch (Exception ex)
			{
				// a few enum entries may not resolve on all revisions; skip them
			}
		}

		if (quests.size() == 0)
		{
			return; // nothing readable yet
		}

		// Achievement diaries (12 areas x 4 tiers) and combat achievement tiers. CA is tier-level
		// only here, since per-task completion is not exposed as a varbit.
		final JsonObject diaries = safeGroup("achievement diaries", this::readDiaries);
		// Karamja EASY/MEDIUM/HARD have task counters but no completion varbit. Sending the
		// counters lets the server show real progress rather than a wrong tick.
		final JsonObject diaryCounts = safeGroup("karamja counters",
			() -> readVarbitGroup(VarbitID.class, "KARAMJA_", "_COUNT"));
		final JsonObject combat = safeGroup("combat tiers", () -> readTiers(CA_TIER_STATUS));
		final JsonObject combatCounts = safeGroup("combat counts", () -> readTiers(CA_TIER_COUNT));
		final JsonObject collection = safeGroup("collection log flags",
			() -> readVarbitGroup(VarbitID.class, "COLLECTION_", "_COMPLETED"));
		final JsonObject skills = safeGroup("skills", this::readSkills);
		final JsonObject caTasks = new JsonObject();
		caSeen.forEach(caTasks::addProperty);
		final JsonObject collectionPagesJson = new JsonObject();
		collectionPages.forEach(collectionPagesJson::add);
		final JsonObject collectionGroupsJson = new JsonObject();
		collectionGroups.forEach(collectionGroupsJson::add);

		// Change detection and the stability check have to cover everything that gets sent, or a
		// diary completed with no quest change would never sync.
		final String questsJson = quests.toString() + diaries + diaryCounts + combat + combatCounts
			+ collection + collectionPagesJson + collectionGroupsJson + skills + caTasks
			+ collectionItems;

		// Two consecutive identical reads are required before a snapshot is trusted. A client
		// still streaming quest varbits produces a different result each time, so a half-loaded
		// state cannot satisfy this.
		if (!questsJson.equals(pendingQuestsJson))
		{
			pendingQuestsJson = questsJson;
			log.debug("Waypoint: reading not yet stable, re-checking in {}s", CONFIRM_DELAY_SECONDS);
			scheduleSync(CONFIRM_DELAY_SECONDS);
			return;
		}

		if (questsJson.equals(lastQuestsJson))
		{
			return; // confirmed, but unchanged since the last successful sync
		}

		final JsonObject payload = new JsonObject();
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			payload.addProperty("rsn", client.getLocalPlayer().getName());
		}
		payload.add("quests", quests);
		payload.add("diaries", diaries);
		payload.add("diaryCounts", diaryCounts);
		payload.add("combat", combat);
		payload.add("combatCounts", combatCounts);
		payload.add("collection", collection);
		payload.add("collectionPages", collectionPagesJson);
		payload.add("collectionGroups", collectionGroupsJson);
		final JsonObject colItems = new JsonObject();
		collectionItems.forEach(colItems::addProperty);
		payload.add("collectionItems", colItems);
		payload.add("skills", skills);
		payload.add("caTasks", caTasks);
		payload.addProperty("caTaskCount", safeInt(this::completedTaskCount));

		post(payload, questsJson);
	}

	/**
	 * Where to sync to. The system property is for running against a local dev server
	 * (-Dwaypoint.url=http://localhost:8000) and is not exposed as a config item.
	 */
	static String baseUrl()
	{
		return System.getProperty("waypoint.url", DEFAULT_URL).replaceAll("/+$", "");
	}

	private void post(JsonObject payload, String questsJson)
	{
		final String base = baseUrl();
		final Request request = new Request.Builder()
			.url(base + "/api.php?do=ingest")
			.header("Authorization", "Bearer " + config.token())
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(@Nonnull Call call, @Nonnull IOException e)
			{
				log.warn("Waypoint: request failed", e);
			}

			@Override
			public void onResponse(@Nonnull Call call, @Nonnull Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (response.isSuccessful())
					{
						lastQuestsJson = questsJson; // only cache on success
						log.debug("Waypoint: synced ({})", body != null ? body.string() : response.code());
					}
					else
					{
						if (response.code() == 429)
						{
							// Treat as delivered for change detection, since retrying the same
							// payload immediately is what caused the throttle.
							lastQuestsJson = questsJson;
						}
						log.warn("Waypoint: server returned {} {}", response.code(), body != null ? body.string() : "");
					}
				}
				catch (IOException e)
				{
					log.warn("Waypoint: error reading response", e);
				}
			}
		});
	}
}
