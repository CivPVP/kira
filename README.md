# Kira (CivPVP fork)

A slash-command-complete fork of [CivMC/Kira](https://github.com/CivMC/Kira), the
Discord bot that bridges Discord and a Minecraft server via the KiraBukkitGateway
plugin over RabbitMQ.

This fork ports every prefix command (`!kira …`) from upstream to Discord slash
commands, drops the legacy prefix dispatch path, and replaces the upstream Maven
build with a Gradle + Docker workflow suited for self-hosting on CivPVP infra.

## What this fork changes vs. upstream

- All 26 commands are slash commands; the `!kira` prefix tree is gone.
- Gradle build (`./gradlew distTar`) instead of Maven.
- Vendored `Dockerfile` and `docker-compose.yml` for one-command local boot.
- CI workflows removed; CivPVP runs its own deploy pipeline (Ansible).

## Local development

See [`dev/README.md`](dev/README.md) for the full per-developer setup
(Discord application, test guild, config, `docker compose up`).

## Deployment

Self-hosted via CivPVP's Ansible infrastructure (separate repo). This repo
intentionally ships no GitHub Actions; do not add publish workflows without
discussing where the image should land.

## Licensing

Dual-licensed, preserving upstream terms:

- Java code: BSD-3-Clause — see [`LICENSE.txt`](LICENSE.txt)
- Kotlin code: MIT — see [`LICENSE.kotlin.txt`](LICENSE.kotlin.txt)

## Upstream

<https://github.com/CivMC/Kira> — file feature requests and upstream-relevant
bugs there. Fork-specific issues (slash command behavior, CivPVP deploy)
belong in this repo.
