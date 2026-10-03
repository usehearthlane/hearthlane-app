# AGENTS.md

## Project Overview

Hearthlane is an open-source Android application providing a private, user-facing way to access personal data and resources hosted on infrastructure controlled by the user.

**Hearthlane is not a homelab management application.**

The homelab, NAS, home server, or other self-hosted infrastructure is the means by which the user retains control of their data. Hearthlane is the experience that makes those resources accessible from anywhere without requiring private services to be exposed directly to the public Internet.

Tagline:

> Your private way home.

## Primary Goal

Make self-hosted personal resources convenient and private to access from anywhere.

The core proposition is:

> My personal data stays in my infrastructure, but I can access it from anywhere, privately.

Prioritize user-facing personal capabilities over infrastructure administration.

## Current Priority

The immediate development priority is **completing the Frigate integration**.

Target flow:

```text
Home
  -> Cameras
      -> Camera
          -> Live view
          -> Recent events
              -> Event details
                  -> Playback
```

It must work both on the local network and remotely through Tailscale. The user should not need to understand which connectivity path is being used.

## Current Frigate Scope

Prioritize:

1. recent events;
2. event thumbnails and metadata;
3. event details;
4. event playback;
5. basic filtering when justified;
6. clear empty/error/offline states;
7. reliable navigation between camera, event list, event detail, and playback.

Do not reproduce the Frigate administration UI.

Do not introduce generic service discovery merely to support this work.

## Product Vision

Hearthlane will eventually provide private access to:

- family location;
- home cameras and security events;
- documents;
- photos;
- videos;
- other personal resources.

Existing self-hosted services are implementation details whenever possible.

Examples:

```text
Cameras   -> Frigate
Photos    -> Immich
Documents -> Nextcloud
```

The user should interact with the capability, not be forced to understand the underlying service.

## Product Boundary

Hearthlane should NOT become:

- a Proxmox dashboard;
- a Docker management interface;
- a Portainer replacement;
- a network administration interface;
- a monitoring dashboard;
- a generic service launcher;
- a generic homelab dashboard.

A service existing in the user's infrastructure is not sufficient reason to add it to Hearthlane.

## Service Discovery

Automatic discovery of services is not a current product requirement.

Do not introduce a generic service discovery framework unless a concrete user-facing requirement demonstrates that it is necessary.

Prefer explicit integrations and adapters.

## Connectivity

Tailscale is currently the private connectivity layer.

Treat Tailscale as infrastructure, not as the product.

Avoid leaking Tailscale-specific concepts into user-facing flows unless necessary.

Do not expose private services publicly merely to simplify implementation.

Preserve the existing LOCAL -> Tailscale fallback behavior unless a deliberate architectural decision changes it.

## Architecture Principles

### User experience over infrastructure

Hide infrastructure complexity from users.

### Private by default

Avoid public exposure of personal services.

### Capability over administration

Expose useful personal capabilities, not infrastructure management.

### Focused adapters

Integrate existing services through focused adapters rather than creating a premature generic service framework.

### Avoid premature abstraction

Do not build generic discovery, plugin, service registry, or homelab-management abstractions without a demonstrated requirement.

### Incremental delivery

Prefer a complete, reliable experience for one capability over partially implementing many capabilities.

## Non-Goals

The following are not current goals:

- managing the user's homelab;
- discovering every service on the user's network;
- replacing Proxmox;
- replacing Docker management tools;
- replacing Frigate administration;
- becoming a generic launcher for internal applications;
- exposing every self-hosted service through a common dashboard.

## Roadmap

1. Frigate integration;
2. Settings organization;
3. New-version/update experience;
4. family location;
5. documents/photos/videos;
6. additional personal capabilities based on concrete user needs.

This roadmap is directional. Do not implement future features merely because they appear on the list.

## Development Rules

Before changing architecture:

1. inspect the current implementation;
2. identify existing abstractions;
3. prefer extending a proven pattern over introducing a new generic layer;
4. preserve existing behavior unless the task explicitly changes it;
5. keep user-facing infrastructure details out of the UI.
6. Do not anticipate identity, authentication, or authorization mechanisms until a concrete integration requires it.

For integrations, first determine the minimum API surface required by the user experience.

For errors, distinguish between:

- no data;
- unavailable service;
- unavailable network;
- expired/missing resource;
- playback failure.

Do not collapse these states into a generic failure when the UI can communicate them meaningfully.

## Release / Compliance

The project is GPL-3.0-only.

Before releases, preserve the procedures documented in `docs/RELEASE.md`, including corresponding-source and third-party-notice requirements.

Never commit signing credentials, keystores, tokens, private keys, local editor configuration changes, or other secrets.

### Release notes and public artifacts

Always write release notes in English, like every other artifact created for the project. This applies to GitHub Releases, changelogs, announcements, and any public-facing text: never produce release notes in another language. If release notes are drafted in a conversation language first, translate them to English before publishing.

## Historical Documents

`docs/PLAN.md` contains historical Decision Log entries.

Do not rewrite historical decisions merely to make them match the current terminology. Record new strategic decisions as new entries.

`docs/PROJECT.md` is the detailed project-vision reference.

# Android UAT Deployment and Data Preservation

These rules apply to the Hearthlane Android UAT environment.

## UAT identity

UAT package:

- `org.hearthlane.uat`

Default UAT APK:

- `app/build/outputs/apk/uat/debug/app-uat-debug.apk`

The UAT application sandbox is NOT disposable.

It may contain persistent development state, including:

- embedded Tailscale/tsnet node identity;
- application settings;
- preferences;
- databases;
- other state required for physical validation.

In particular:

- `files/tailscale/tailscaled.state`

contains persistent tsnet state and MUST be treated as important data.

Never assume that uninstalling the UAT application is harmless.

---

## General deployment rule

When asked to build, install, reinstall, update, or deploy UAT to a
physical Android device, always prefer preserving the existing
application sandbox.

The normal deployment flow is:

1. build the requested UAT APK;
2. verify the connected device;
3. inspect the currently installed UAT package if present;
4. attempt an in-place update;
5. only perform backup → uninstall → reinstall → restore when required
   because of signing-key incompatibility.

Never uninstall UAT preemptively.

---

## Normal UAT build

Unless another build is explicitly requested, build UAT using:

```bash
./gradlew :app:assembleUatDebug