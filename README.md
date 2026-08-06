# Iron Path RuneLite plugin

The companion plugin synchronizes real skill XP, quest states, bank/inventory/equipment snapshots, observed NPC kills, and loot to the Iron Path web app. It does not read or store Jagex, RuneLite, Discord, or email credentials.

The web application and API live in the separate [`iron-path`](https://github.com/ConnorFitzgerald17/iron-path) repository.

## Development

Requirements: Java 11 and Gradle, or a Gradle wrapper generated with a compatible Gradle installation.

```sh
gradle test
gradle run
```

For local web development, set **API origin** to `http://localhost:3000`. Generate a linking code on the web, paste it into plugin settings, and press **Connect account**. With the web app in demo mode, its exchange endpoint returns the scoped `demo-device-token`.

## Sync behavior

- Skills and quests: login, manual sync, and every five minutes.
- Bank: two seconds after the last bank container change.
- Inventory/equipment: included in the next snapshot.
- NPC loot: queued immediately, capped at 250 offline events, and retried without duplicate server records.
- Tokens and offline queues: stored against RuneLite's current RuneScape profile.

The project uses Plugin Hub standard build mode and no dependencies beyond RuneLite's transitive dependency set.
