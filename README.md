# Waypoint Sync

Keeps your [Waypoint](https://waypointosrs.com) profile in step with your account, so you don't
have to fill in a checklist by hand.

Waypoint is a progression tracker built around one question: "I've got an hour, what should I
actually do?" It can only answer that if it knows where you are, and this plugin is where that
comes from. It reads quests, diaries, combat achievements, skills and collection log from the
client and uploads them.

**Nothing happens until you paste a token.** With the token field empty the plugin reads no game
state and makes no network requests.

---

## What this plugin sends

All of it goes to one endpoint, `POST https://waypointosrs.com/api.php?do=ingest`, authenticated
with your plugin token. There is no setting to point it anywhere else.

| Data | Source | Why |
|---|---|---|
| Display name | `client.getLocalPlayer().getName()` | Labels the profile, and separates two accounts |
| Quest states | `Quest.getState()` for every quest | The core checklist |
| Achievement diaries | `VarbitID.*_DIARY_*_COMPLETE` | Which tiers are done |
| Karamja task counts | `VarbitID.KARAMJA_*_COUNT` | Karamja's easy/medium/hard tiers have no completion varbit, only counters |
| Combat achievements | `VarbitID.CA_TIER_STATUS_*`, `CA_TOTAL_TASKS_COMPLETED_*`, and the 20 `CA_TASK_COMPLETED_*` varps | Tier status and total completed |
| Combat task names | Text in the CA Tasks interface, while you have it open | Per-task detail, which is not a varbit |
| Skills | `getRealSkillLevel` and `getSkillExperience`, all 24 | Requirement checks, and it saves a hiscores lookup |
| Collection log counts | The `Obtained: x/y` label on a page you open, plus the five overview totals | Progress per page |
| Collection log items | The game's `New item added to your collection log: X` message, and item widgets on a page you open | Which specific drops you have |

Sends happen on login (after a delay, see below), every few minutes during a session, and when a
capture changes something. Identical state is not re-sent.

## What it does not send

- **No chat.** There is one `ChatMessage` subscriber. It matches the game-generated line
  `New item added to your collection log: <item>` and returns immediately on anything else. No
  player chat, private messages or clan chat is read, stored or transmitted.
- **No bank, inventory, equipment, position, or world.**
- **No password, Jagex credential, session token, or email.**
- **Nothing about other players.**

## What it does inside the client

Nothing that affects gameplay. No overlay, no input handling, no menu entries, and nothing is ever
sent to the game server. It reads varbits and varps, reads text and item ids from two interfaces
you have opened yourself, and makes an HTTPS request on RuneLite's shared `OkHttpClient`.

## Privacy and control

- The token is a bearer credential for one Waypoint account. It is marked `secret = true`, so it
  is masked in the settings panel and excluded from config export and profile sync.
- Regenerating the token on the site invalidates the old one immediately.
- Clearing the token, disabling the plugin, or uninstalling it stops all uploads.
- Deleting your Waypoint account deletes the data already uploaded.
- Profiles are private by default. Sharing one is an explicit action that produces a revocable
  link.

---

## Setup

1. Make an account at [waypointosrs.com](https://waypointosrs.com).
2. Go to **Import > Auto-sync > Generate** for your plugin token.
3. Install **Waypoint Sync** from the Plugin Hub.
4. Paste the token into the plugin's settings.
5. Log in. Your progress shows up on the site within a minute.

To fill in collection log and combat achievement detail, open the collection log and click through
the pages you care about, and open Combat Achievements > Tasks once. Those screens are the only
place that data exists.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Plugin token | *(empty)* | From Import > Auto-sync on the site. Nothing uploads while empty. |
| Sync on login | on | Push progress each time you log in |
| Login sync delay (sec) | 30 | Wait before the first sync, since quest varbits load late |
| Re-sync every (min) | 5 | Periodic re-sync during a session, 0 disables |

---

## Implementation notes

**Quest varbits load after `LOGGED_IN`.** They stream in over several seconds, so reading at the
moment the state changes gives a partial snapshot. Ingest is authoritative, so that would clear
quests that are actually finished. Two defences: the configurable login delay, and a rule that two
consecutive reads must agree before anything is sent. A client that is still loading returns
something different each time, so it can't satisfy the second.

**Diary varbits are gathered by scanning field names** rather than listing 48 of them. `VarbitID`
is plain, non-obfuscated API with `public static final int` constants, so this is a name-prefix
scan over public fields and nothing more. There is no `setAccessible` anywhere. The benefit is
that a new diary area is picked up on its own instead of going quietly missing.

**Combat achievement tiers are listed explicitly** rather than scanned, because
`CA_TOTAL_TASKS_COMPLETED_` also matches dozens of per-boss counters that would end up in the
payload as junk keys.

**Three Karamja diary tiers are deliberately absent.** The legacy `Varbits.DIARY_KARAMJA_*` entries
are task counters, not completion flags: they read 1 after a single task, which reports the tier
complete for an account that has barely started it. The `VarbitID` namespace has no Karamja
easy/medium/hard entry at all, so those three are not sent and the server leaves them alone. Real
progress goes in the counts instead.

**Per-task combat achievement completion is not readable as a varbit.** The named
`CA_TASK_*_COMPLETED` entries cover about 63% of tasks, and the 640-bit varplayer bitmap has no
published task-to-bit mapping. So task names are read from the interface while it's open, and the
bitmap is used only for a total, which lets the site tell when its per-task list is stale.

**The 20 CA varps are listed explicitly** because the ids are not consecutive: 3116-3128, then
3387, 3718, 3773, 3774, 4204, 4496, 4721. Reading them as `base + i` picks up unrelated varps.

**Collection log capture avoids widget child ids**, which shift between game updates. It walks the
interface text and matches the `Obtained: x/y` label the log renders itself. The page title is the
nearest preceding label in document order.

**The overview lays out all five labels first, then all five counts**, not label/count pairs, so
scanning forward from a label mis-assigns them. Labels and counts are collected separately and
paired by position, and only accepted when there are five labels and at least five counts.

**Item state is sent raw.** Unobtained items are drawn dimmed, but the exact opacity semantics are
an assumption, so the raw value is sent and interpreted server-side. An item confirmed by chat is
recorded as `-1` and never overwritten by an opacity reading.

**`gameval` classes are guarded.** They are compiled against, but RuneLite loads
`net.runelite.api` from the injected client it downloads at startup, which can shadow them. On an
older client they throw `NoClassDefFoundError` at runtime, so each group of varbits is read inside
its own try/catch and losing diaries can't also lose quests.

**The login sync has a tick-based fallback.** `GameStateChanged` is dispatched to subscribers in
sequence and an exception in one aborts the rest, so an unrelated plugin throwing in `onLogin` can
stop `LOGGED_IN` from arriving here. `GameTick` isn't affected by that.

---

## Building locally

Standard external-plugin flow. Needs a JDK in Gradle 8.10's supported range (Java 8-23); JDK 21
LTS is the safe choice, since Lombok 1.18.30 tops out there.

```sh
./gradlew runClient    # launches RuneLite with the plugin loaded
./gradlew build        # compile and test
```

Output is Java 11 bytecode (`options.release.set(11)`) regardless of which JDK builds it.

Point the plugin at a dev server with `-Dwaypoint.url=http://localhost:8000`. It's a system
property, not a config item.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
