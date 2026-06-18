# MediaSage Player — Android

> **⚠️ Heads up — this builds a client for a *private, password-protected* music
> backend. It is not a standalone app and won't do anything useful on its own.**
> Each build is compiled for one specific person and points only at *their* own
> self-hosted server, which requires a login. With no server address and no
> credentials (none of which are in this repository), a build you make yourself
> just opens an empty placeholder. Cloning this gives you the wrapper's UI code —
> **not access to anyone's server, library, or data.**

A small native Android wrapper around a self-hosted web music player. It runs the
player in its own process with a **foreground media service**, so Android keeps the
audio alive with the screen off and gives you lock-screen + notification controls —
fixing the stutter you get when the player is just a background browser tab.

## How it works
- The app is a full-screen WebView that loads the player live from the configured
  server and bridges the page to native media controls.
- A small injected script (`bridge.js`) mirrors now-playing to the native service
  and applies mobile styling over the live page — it never modifies the served page.

## What is NOT in this repository
This is the deliberate security boundary — nothing here grants access to a server:
- **No server addresses.** The per-build URL is injected at build time from a
  private GitHub Actions secret; it only ever ends up inside the locally-built
  installer of the person it was built for.
- **No usernames and no passwords.** Basic-auth credentials come from private
  build secrets; none are in the source or the git history.
- **No signing key.** The keystore is restored at build time from a secret.
- **No library, listening, or personal data, and no player UI** — the player is
  loaded live from the owner's server at runtime; it isn't bundled here.

## Build
APKs are built by GitHub Actions (`.github/workflows/build-apk.yml`) — one
debug-signed installable per build target — and can be run on demand from the
Actions tab. A plain clone with no secrets configured builds an app pointing at an
empty placeholder.

Stack: native Android (Kotlin) + system WebView, foreground `MediaSession` service.
