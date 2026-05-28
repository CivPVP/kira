# Local dev environment

> Prerequisite: Task 2 (vendoring upstream Kira source) must have completed — it brings in `Dockerfile`, `docker-compose.yml`, and the Gradle build files this README depends on.

## One-time Discord setup

1. https://discord.com/developers/applications → New Application → name it `kira-dev-<yourhandle>`.
2. Bot tab: Add Bot. Reveal token. **Enable** the `MESSAGE_CONTENT` and `SERVER_MEMBERS` privileged intents.
3. OAuth2 → URL Generator: scopes `bot` + `applications.commands`, permissions `Administrator`. Open the URL, invite to a brand-new throwaway Discord server you create for testing.
4. In the test server: create a role named `kira-admin`; assign it to yourself. Note the server (guild) ID and the role ID via Developer Mode → right-click → Copy ID.

## Per-session

1. `cp dev/config.example.json dev/config.json` and fill in real values (token, guild ID, auth role ID = the `kira-admin` role ID for now).
2. `cp dev/config.json config.json` — the upstream `docker-compose.yml` mounts `./config.json` into the kira container.
3. `mise exec -- ./gradlew distTar` — builds `build/distributions/kira-<version>.tar` that the Dockerfile consumes.
4. `docker compose up --build` — boots Postgres + RabbitMQ + Kira. Wait until the kira container stops printing init logs and slash commands appear in your test guild's autocomplete (5–10 seconds).
5. Smoke-test commands in your test guild. Ctrl-C and `docker compose down` to stop containers. To wipe Postgres state, also `rm -rf local/` (compose uses a bind mount, not a named volume, so `-v` is a no-op).

## Notes

- `dev/config.json` is gitignored. Never commit a real bot token.
- The test bot will register slash commands into your test guild on each startup. Global command propagation can take up to 1 hour; guild-scoped commands appear within ~5 seconds.
