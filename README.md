# Iron Path RuneLite plugin

The companion plugin synchronizes real skill XP, quest states, bank/inventory/equipment snapshots, Collection Log counts, observed NPC kills, and loot to the Iron Path web app. It does not read or store Jagex, RuneLite, Discord, or email credentials.

The web application and API are live at [ironpathosrs.com](https://www.ironpathosrs.com/) and maintained in the separate [`iron-path`](https://github.com/ConnorFitzgerald17/iron-path) repository.

## Data and privacy

Iron Path does not send game data until you explicitly link the logged-in RuneScape profile with a single-use code from the website. Once linked, the plugin sends the following data to the configured **API origin** over HTTPS:

- RuneScape character name, exact account mode, combat level, total level, individual skill levels, and XP.
- Quest states plus bank, inventory, and equipment item IDs and quantities.
- Observed NPC kills, loot item IDs and quantities, and Collection Log item counts.
- Iron Path goals and the live progress used to display or update them.

The plugin does not read or send Jagex login details, RuneLite credentials, email credentials, or Discord credentials. The API device token, its issuing HTTPS origin, and retry queues are stored in RuneLite's per-character profile storage, which prevents one linked character from synchronizing into another character's journal. Changing **API origin** requires relinking and never sends an existing origin's token to the new server.

## Development

Requirement: Java 11. The repository includes a pinned Gradle 8.10.2 wrapper, so a global Gradle installation is not needed. On macOS, the wrapper automatically discovers Homebrew's `openjdk@11` installation.

For IDE configuration on this Intel Mac, the installed JDK home is `/usr/local/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home`.

```sh
./gradlew test
```

This compiles the plugin and runs its unit tests without installing or launching the RuneLite desktop client. When you are ready to test inside RuneLite developer mode, use `./gradlew run`.

For local web development, expose the local application through HTTPS and set **API origin** to that secure origin. Generate a verification code on the web, paste it into plugin settings, log into the character being claimed, and press **Connect**. The verified RuneLite identity creates the journal on first sync. With the web app in demo mode, its exchange endpoint returns the scoped `demo-device-token`.

## Sync behavior

- RSN, exact account mode, combat/total levels, skills, quests, inventory, and equipment: login, manual sync, and every two minutes when automatic sync is enabled.
- Dashboard goals: startup, login, manual sync, and every 15 seconds while automatic sync is enabled.
- Bank: two seconds after the last bank container change.
- Collection Log: open the Collection Log overview once so RuneLite can expose its latest items, then open the full log and use Iron Path's **Sync Collection Log** button. The plugin captures the authoritative full-log result, keeps the last successfully read recent-items list when the overview is unavailable, queues the newest copy of every section, and retries failed uploads against the active RuneScape profile.
- NPC kills and loot: recorded from RuneLite loot events, delivered immediately in batches, and retried without duplicate dashboard records.
- Kill totals: retained per NPC after upload so recent activity and grind progress survive client restarts.
- Offline queue: capped at 500 events and flushed in batches of 100 to keep requests small.
- Tokens, kill totals, sync timestamps, and loot/Collection Log offline queues: stored against RuneLite's current RuneScape profile.

Link each RuneScape character to its chosen web journal once, then explicitly enable **Automatic sync** if desired. After that, changing RuneScape profiles automatically loads the matching scoped token; an unlinked profile remains disconnected and cannot overwrite another journal.

## Plugin panel

The sidebar shows session KC, queued events, Collection Log sync status, the last successful sync time, recent NPC totals, and live goal progress. Grind goals combine their starting KC with tracked RuneLite kills, quest goals use the live quest state, and skill/banked-XP goals use live skill XP. Skill-goal completion is derived and cannot be manually overridden; other active goals can be completed from RuneLite without overwriting dashboard settings.

The project uses Plugin Hub standard build mode and no dependencies beyond RuneLite's transitive dependency set.
