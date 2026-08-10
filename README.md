# Iron Path RuneLite plugin

The companion plugin synchronizes real skill XP, quest states, bank/inventory/equipment snapshots, Collection Log counts, observed NPC kills, and loot to the Iron Path web app. It does not read or store Jagex, RuneLite, Discord, or email credentials.

The web application and API live in the separate [`iron-path`](https://github.com/ConnorFitzgerald17/iron-path) repository.

## Development

Requirement: Java 11. The repository includes a pinned Gradle 8.10.2 wrapper, so a global Gradle installation is not needed. On macOS, the wrapper automatically discovers Homebrew's `openjdk@11` installation.

For IDE configuration on this Intel Mac, the installed JDK home is `/usr/local/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home`.

```sh
./gradlew test
```

This compiles the plugin and runs its unit tests without installing or launching the RuneLite desktop client. When you are ready to test inside RuneLite developer mode, use `./gradlew run`.

For local web development, set **API origin** to `http://localhost:3000`. Generate a linking code on the web, paste it into plugin settings, and press **Connect account**. With the web app in demo mode, its exchange endpoint returns the scoped `demo-device-token`.

## Sync behavior

- RSN, exact account mode, combat/total levels, skills, quests, inventory, and equipment: login, manual sync, and every two minutes.
- Dashboard goals: startup, login, manual sync, and every 15 seconds while automatic sync is enabled.
- Bank: two seconds after the last bank container change.
- Collection Log: open the log and use its native **Search** button once. The plugin captures the authoritative full-log result, queues the newest copy of every section, and retries failed uploads against the active RuneScape profile.
- NPC kills and loot: recorded from RuneLite loot events, delivered immediately in batches, and retried without duplicate dashboard records.
- Kill totals: retained per NPC after upload so recent activity and grind progress survive client restarts.
- Offline queue: capped at 500 events and flushed in batches of 100 to keep requests small.
- Tokens, kill totals, sync timestamps, and loot/Collection Log offline queues: stored against RuneLite's current RuneScape profile.

Link each RuneScape character to its chosen web journal once. After that, changing RuneScape profiles automatically loads the matching scoped token; an unlinked profile remains disconnected and cannot overwrite another journal.

## Plugin panel

The sidebar shows session KC, queued events, Collection Log sync status, the last successful sync time, recent NPC totals, and live goal progress. Grind goals combine their starting KC with tracked RuneLite kills, quest goals use the live quest state, and skill/banked-XP goals use live skill XP. Skill-goal completion is derived and cannot be manually overridden; other active goals can be completed from RuneLite without overwriting dashboard settings.

The project uses Plugin Hub standard build mode and no dependencies beyond RuneLite's transitive dependency set.
