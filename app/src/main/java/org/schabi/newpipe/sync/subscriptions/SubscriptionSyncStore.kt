package org.schabi.newpipe.sync.subscriptions

import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.database.subscription.SubscriptionMembership
import org.schabi.newpipe.database.subscription.SubscriptionSyncRevision
import org.schabi.newpipe.extractor.subscription.SubscriptionItem
import java.util.concurrent.Callable
import kotlin.math.absoluteValue

internal class SubscriptionSyncStore(private val db: AppDatabase) {
    fun apply(snapshot: SubscriptionSnapshot): Boolean {
        return db.runInTransaction(Callable {
            val dao = db.subscriptionDAO()
            if (!canApplySubscriptionRevision(snapshot.revision, dao.acceptedRevision(snapshot.origin))) {
                return@Callable false
            }

            val existing = dao.syncCandidates(SubscriptionSnapshot.serviceIds()).asReversed().mapNotNull {
                val service = SubscriptionSnapshot.serviceKey(it.serviceId) ?: return@mapNotNull null
                val url = runCatching { SubscriptionSnapshot.canonicalUrl(service, it.url) }.getOrNull()
                    ?: return@mapNotNull null
                (service to url) to it.uid
            }.toMap()
            val oldIds = dao.memberIds(snapshot.origin)
            val presentIds = snapshot.channels.mapTo(HashSet()) { channel ->
                val serviceId = SubscriptionSnapshot.serviceId(channel.service)
                val id = dao.exactId(serviceId, channel.url) ?: existing[channel.key]
                    ?: dao.insert(SubscriptionEntity.from(SubscriptionItem(serviceId, channel.url, channel.name)))
                dao.addMembership(SubscriptionMembership(id, snapshot.origin))
                id
            }
            for (id in oldIds) {
                if (id in presentIds) continue
                dao.removeMembership(id, snapshot.origin)
                dao.deleteIfUnowned(id)
            }
            dao.setAcceptedRevision(SubscriptionSyncRevision(snapshot.origin, snapshot.revision))
            true
        })
    }

    fun purge(): Int {
        return db.runInTransaction(Callable {
            val dao = db.subscriptionDAO()
            val count = dao.deletePeerOnly()
            dao.deletePeerMemberships()
            dao.allowAcceptedRevisionReplay()
            count
        })
    }
}

// Purge negates the watermark to allow that same snapshot to materialize once again.
internal fun canApplySubscriptionRevision(incoming: Long, stored: Long?): Boolean {
    val revision = stored ?: 0
    return incoming > revision.absoluteValue || (revision < 0 && incoming == -revision)
}
