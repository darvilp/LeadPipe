package org.schabi.newpipe.sync.subscriptions

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.preference.PreferenceManager
import org.json.JSONObject
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal data class SyncSession(val deviceId: String, val generation: Long)
internal data class OutgoingSubscriptions(val snapshot: SubscriptionSnapshot, val skipped: Int)
internal data class SubscriptionPeer(val id: String, val pin: String, val send: Boolean, val receive: Boolean)

object SubscriptionSync {
    private var generation = 0L

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "subscription-sync.json"))

    private fun settings(context: Context): JSONObject = try {
        JSONObject(file(context).readFully().toString(Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        JSONObject()
    }

    private fun save(context: Context, state: JSONObject) {
        val file = file(context)
        val output = file.startWrite()
        try {
            output.write(state.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    @JvmStatic
    @Synchronized
    fun isEnabled(context: Context): Boolean = settings(context).optBoolean("enabled")

    @Synchronized
    internal fun enable(context: Context) {
        check(PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.debug_sync_server_key), "").isNullOrBlank()) {
            "Clear the experimental database sync server before enabling subscription sync."
        }
        val state = settings(context)
        if (!state.has("id")) state.put("id", UUID.randomUUID().toString())
        save(context, state.put("enabled", true))
    }

    @Synchronized
    internal fun session(context: Context): SyncSession {
        val state = settings(context)
        check(state.optBoolean("enabled")) { "Subscription sync is disabled" }
        return SyncSession(state.getString("id"), generation)
    }

    private fun requireActive(context: Context, session: SyncSession): JSONObject {
        val state = settings(context)
        check(state.optBoolean("enabled") && session.generation == generation
            && state.optString("id") == session.deviceId) { "Subscription sync was paused" }
        return state
    }

    @Synchronized
    internal fun outgoing(context: Context, session: SyncSession): OutgoingSubscriptions {
        val state = requireActive(context, session)
        val subscriptions = NewPipeDatabase.getInstance(context).subscriptionDAO().localSubscriptions()
        val supported = subscriptions.mapNotNull {
            val service = SubscriptionSnapshot.serviceKey(it.serviceId) ?: return@mapNotNull null
            val url = runCatching { SubscriptionSnapshot.canonicalUrl(service, it.url) }.getOrNull()
                ?: return@mapNotNull null
            SnapshotChannel(service, url, it.name.orEmpty().ifBlank { url }.take(1024))
        }
        val revision = Math.addExact(state.optLong("revision"), 1)
        val snapshot = SubscriptionSnapshot(session.deviceId, revision, supported.distinctBy { it.key })
        check(snapshot.channels.size <= SubscriptionSnapshot.MAX_CHANNELS &&
            snapshot.encode().toByteArray(Charsets.UTF_8).size <= SubscriptionSnapshot.MAX_BYTES) {
            "Subscription snapshot is too large"
        }
        save(context, state.put("revision", revision))
        return OutgoingSubscriptions(snapshot, subscriptions.size - supported.size)
    }

    @Synchronized
    internal fun apply(
        context: Context,
        session: SyncSession,
        snapshot: SubscriptionSnapshot,
        lan: Boolean = false
    ): Boolean {
        requireActive(context, session)
        if (snapshot.origin == session.deviceId) return false
        check(!lan || peer(context, snapshot.origin)?.receive == true) { "Receiving is disabled for this peer" }
        return SubscriptionSyncStore(NewPipeDatabase.getInstance(context)).apply(snapshot)
    }

    @Synchronized
    internal fun purgeCount(context: Context): Int =
        NewPipeDatabase.getInstance(context).subscriptionDAO().peerOnlyCount()

    @Synchronized
    internal fun purge(context: Context): Int {
        pause(context)
        return SubscriptionSyncStore(NewPipeDatabase.getInstance(context)).purge()
    }

    @Synchronized
    fun clearAll(context: Context) {
        pause(context)
        val db = NewPipeDatabase.getInstance(context)
        db.runInTransaction {
            db.subscriptionDAO().deleteAll()
            db.subscriptionDAO().clearAcceptedRevisions()
        }
    }

    private fun pause(context: Context) {
        val state = settings(context)
        if (state.optBoolean("enabled")) save(context, state.put("enabled", false))
        generation++
    }

    @Synchronized
    internal fun directory(context: Context): Uri? =
        settings(context).optString("directory").takeIf { it.isNotEmpty() }?.let(Uri::parse)

    @Synchronized
    internal fun setDirectory(context: Context, uri: Uri) =
        save(context, settings(context).put("directory", uri.toString()))

    @Synchronized
    internal fun peer(context: Context, id: String): SubscriptionPeer? {
        val peer = settings(context).optJSONObject("peers")?.optJSONObject(id) ?: return null
        return SubscriptionPeer(id, peer.getString("pin"), peer.optBoolean("send"), peer.optBoolean("receive"))
    }

    @Synchronized
    internal fun knowsPin(context: Context, pin: String): Boolean {
        val peers = settings(context).optJSONObject("peers") ?: return false
        return peers.keys().asSequence().any { peers.optJSONObject(it)?.optString("pin") == pin }
    }

    @Synchronized
    internal fun savePeer(context: Context, session: SyncSession, peer: SubscriptionPeer) {
        val state = requireActive(context, session)
        val peers = state.optJSONObject("peers") ?: JSONObject()
        peers.put(peer.id, JSONObject().put("pin", peer.pin).put("send", peer.send).put("receive", peer.receive))
        save(context, state.put("peers", peers))
    }

    @Synchronized
    internal fun forgetPeer(context: Context, session: SyncSession, id: String) {
        val state = requireActive(context, session)
        val peers = state.optJSONObject("peers") ?: return
        peers.remove(id)
        save(context, state)
    }
}
