package com.calllogvault

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The one serial worker for all vault background work: UI syncs,
 * vault loads, sniffer syncs, auto backup job runs, and the SAF
 * export, import and restore runs. A single thread means SQLite
 * access and provider calls never pile up on parallel threads
 * competing with each other (and with the UI thread during scrolls);
 * a slow job can only ever queue behind other vault work, never run
 * alongside it. Process lifetime singleton: never shut down.
 */
object VaultExecutor {
    val service: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "vault-worker").apply { isDaemon = true }
        }
    )
}
