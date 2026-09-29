package org.hearthlane.tailscale

/**
 * Process-global accounting of which consumers currently use the single
 * embedded tsnet node, with the physical start/stop bound atomically to the
 * ownership transitions.
 *
 * The Go node (tsembed) is a singleton for the whole process, but the app has
 * two independent gateways: the foreground UI (Frigate/relay/map) and the
 * location publisher. Without shared accounting, one consumer stopping the
 * node could tear it down while another is still using it.
 *
 * Correctness model: the counter transition AND the physical [start]/[stop]
 * call are one critical section, so a first-start can never cross a
 * last-stop. Concretely:
 * - [acquire] on the 0->1 transition physically starts the node and returns
 *   true; a later consumer just increments and reuses the running node.
 * - [release] on the 1->0 transition physically stops the node and returns
 *   true; with another consumer present it only decrements.
 *
 * [start]/[stop] are injected so the atomicity is unit-testable without the
 * native node (the production singleton wires them to tsembed).
 */
class TsnetOwnership(
    private val start: (hostname: String, authKey: String, stateDir: String) -> Unit,
    private val stop: () -> Unit,
) {

    private val lock = Any()
    private var users = 0

    /** Number of consumers that currently claim the node (diagnostics/tests). */
    val activeUsers: Int
        get() = synchronized(lock) { users }

    /**
     * Registers one consumer use and starts the node when it is the first
     * consumer. Returns true when the caller must be considered the starter of
     * the node (0->1). A throwing [start] leaves the counter untouched so a
     * failed first start leaves no orphan owner.
     */
    fun acquire(hostname: String, authKey: String, stateDir: String): Boolean =
        synchronized(lock) {
            if (users == 0) start(hostname, authKey, stateDir)
            users++
            users == 1
        }

    /**
     * Registers one additional consumer use ONLY while the node already has a
     * physical owner, without ever starting the node. Returns true when the
     * claim was retained; false when no consumer owns the node.
     *
     * This is the enrollment session's primitive: it must protect the exact
     * physical node that produced the pending login URL, so it may never start
     * a node on its own (a fresh start mints a different node key and
     * invalidates the URL). It is deliberately distinct from [acquire], whose
     * 0->1 transition starts the node.
     */
    fun retain(): Boolean = synchronized(lock) {
        if (users == 0) return false
        users++
        true
    }

    /**
     * Releases one consumer use and stops the node when it is the last
     * consumer. Returns true when the caller triggered the stop (1->0).
     * Releasing without a matching acquire is a no-op; the count never goes
     * negative.
     */
    fun release(): Boolean = synchronized(lock) {
        if (users == 0) return false
        users--
        if (users == 0) {
            stop()
            true
        } else {
            false
        }
    }

    /**
     * Drops every consumer claim and forces the node down (administrator
     * identity reset).
     */
    fun reset() {
        synchronized(lock) {
            users = 0
            stop()
        }
    }
}