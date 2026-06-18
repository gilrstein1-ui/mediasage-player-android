package com.tulik.player

/**
 * Per-user build. The user's PUBLIC web address + basic-auth credentials are
 * baked in at build time (BuildConfig, injected by CI from that user's secret),
 * so the app opens straight to their player from anywhere — no Tailscale, no
 * picker, no password prompt. Each user gets their OWN installer, so an app file
 * only ever contains that one user's password.
 */
object ServerConfig {
    val baseUrl: String get() = BuildConfig.APP_URL
    val authUser: String get() = BuildConfig.AUTH_USER
    val authPw: String get() = BuildConfig.AUTH_PW
}
