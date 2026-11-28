package org.schabi.newpipe.database.subscription

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Upsert
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import org.schabi.newpipe.database.BasicDAO

@Dao
abstract class SubscriptionDAO : BasicDAO<SubscriptionEntity> {
    @Query("SELECT COUNT(*) FROM subscriptions")
    abstract fun rowCount(): Flowable<Long>

    @Query("SELECT * FROM subscriptions WHERE service_id = :serviceId")
    abstract override fun listByService(serviceId: Int): Flowable<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE ASC")
    abstract override fun getAll(): Flowable<List<SubscriptionEntity>>

    @Query(
        """
        SELECT * FROM subscriptions

        WHERE name LIKE '%' || :filter || '%'

        ORDER BY name COLLATE NOCASE ASC
        """
    )
    abstract fun getSubscriptionsFiltered(filter: String): Flowable<List<SubscriptionEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT * FROM subscriptions s

        LEFT JOIN feed_group_subscription_join fgs
        ON s.uid = fgs.subscription_id

        WHERE (fgs.group_id = :currentGroupId)

        ORDER BY name COLLATE NOCASE ASC
        """
    )
    abstract fun getSubscriptionsForCurrentGroup(
        currentGroupId: Long
    ): Flowable<List<SubscriptionEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT * FROM subscriptions s

        LEFT JOIN feed_group_subscription_join fgs
        ON s.uid = fgs.subscription_id

        WHERE (fgs.group_id = :currentGroupId)
        AND s.name LIKE '%' || :filter || '%'

        ORDER BY name COLLATE NOCASE ASC
        """
    )
    abstract fun getSubscriptionsForCurrentGroupFiltered(
        currentGroupId: Long,
        filter: String
    ): Flowable<List<SubscriptionEntity>>


    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT * FROM subscriptions s

        LEFT JOIN feed_group_subscription_join fgs
        ON s.uid = fgs.subscription_id

        WHERE (fgs.subscription_id IS NULL OR fgs.group_id = :currentGroupId)

        ORDER BY name COLLATE NOCASE ASC
        """
    )
    abstract fun getSubscriptionsOnlyUngrouped(
        currentGroupId: Long
    ): Flowable<List<SubscriptionEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT * FROM subscriptions s

        LEFT JOIN feed_group_subscription_join fgs
        ON s.uid = fgs.subscription_id

        WHERE (fgs.subscription_id IS NULL OR fgs.group_id = :currentGroupId)
        AND s.name LIKE '%' || :filter || '%'

        ORDER BY name COLLATE NOCASE ASC
        """
    )
    abstract fun getSubscriptionsOnlyUngroupedFiltered(
        currentGroupId: Long,
        filter: String
    ): Flowable<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE url LIKE :url AND service_id = :serviceId")
    abstract fun getSubscriptionFlowable(serviceId: Int, url: String): Flowable<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE url LIKE :url AND service_id = :serviceId")
    abstract fun getSubscription(serviceId: Int, url: String): Maybe<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE uid = :subscriptionId")
    abstract fun getSubscription(subscriptionId: Long): SubscriptionEntity?

    @Query("DELETE FROM subscriptions")
    abstract override fun deleteAll(): Int

    @Query("DELETE FROM subscriptions WHERE url LIKE :url AND service_id = :serviceId")
    abstract fun deleteSubscription(serviceId: Int, url: String): Int

    @Query("SELECT uid FROM subscriptions WHERE url LIKE :url AND service_id = :serviceId")
    internal abstract fun getSubscriptionIdInternal(serviceId: Int, url: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract fun silentInsertAllInternal(entities: List<SubscriptionEntity>): List<Long>

    @Transaction
    open fun insertAllIgnoringExisting(
        entities: List<SubscriptionEntity>
    ): List<SubscriptionEntity> {
        val insertUidList = silentInsertAllInternal(entities)

        return entities.mapIndexedNotNull { index, entity ->
            insertUidList[index].takeIf { it != -1L }?.let { uid ->
                entity.apply { this.uid = uid }
            }
        }
    }

    @Query("SELECT uid FROM subscriptions WHERE service_id = :serviceId AND url = :url")
    abstract fun exactId(serviceId: Int, url: String): Long?

    @Query(
        """
        SELECT s.*, EXISTS (SELECT 1 FROM subscription_memberships m
            WHERE m.subscription_id = s.uid AND m.origin = 'local') AS locally_owned
        FROM subscriptions s WHERE s.service_id = :serviceId AND s.url = :url
        """
    )
    abstract fun observeOwnership(serviceId: Int, url: String): Flowable<List<SubscriptionOwnership>>

    @Query(
        "SELECT s.* FROM subscriptions s JOIN subscription_memberships m " +
            "ON m.subscription_id = s.uid WHERE m.origin = 'local' ORDER BY s.uid"
    )
    abstract fun localSubscriptions(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE service_id IN (:serviceIds) ORDER BY uid")
    abstract fun syncCandidates(serviceIds: List<Int>): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun addMembership(membership: SubscriptionMembership)

    @Query("SELECT subscription_id FROM subscription_memberships WHERE origin = :origin")
    abstract fun memberIds(origin: String): List<Long>

    @Query("DELETE FROM subscription_memberships WHERE subscription_id = :id AND origin = :origin")
    abstract fun removeMembership(id: Long, origin: String): Int

    @Query(
        "DELETE FROM subscriptions WHERE uid = :id AND NOT EXISTS " +
            "(SELECT 1 FROM subscription_memberships WHERE subscription_id = :id)"
    )
    abstract fun deleteIfUnowned(id: Long)

    @Query(
        "SELECT COUNT(*) FROM subscriptions WHERE EXISTS " +
            "(SELECT 1 FROM subscription_memberships WHERE subscription_id = uid AND origin != 'local') " +
            "AND NOT EXISTS (SELECT 1 FROM subscription_memberships WHERE subscription_id = uid AND origin = 'local')"
    )
    abstract fun peerOnlyCount(): Int

    @Query(
        "DELETE FROM subscriptions WHERE EXISTS " +
            "(SELECT 1 FROM subscription_memberships WHERE subscription_id = uid AND origin != 'local') " +
            "AND NOT EXISTS (SELECT 1 FROM subscription_memberships WHERE subscription_id = uid AND origin = 'local')"
    )
    abstract fun deletePeerOnly(): Int

    @Query("DELETE FROM subscription_memberships WHERE origin != 'local'")
    abstract fun deletePeerMemberships()

    @Query("SELECT revision FROM subscription_sync_revisions WHERE origin = :origin")
    abstract fun acceptedRevision(origin: String): Long?

    @Upsert
    abstract fun setAcceptedRevision(revision: SubscriptionSyncRevision)

    @Query("DELETE FROM subscription_sync_revisions")
    abstract fun clearAcceptedRevisions()

    @Transaction
    open fun upsertAll(entities: List<SubscriptionEntity>): List<SubscriptionEntity> {
        val insertUidList = silentInsertAllInternal(entities)

        insertUidList.forEachIndexed { index: Int, uidFromInsert: Long ->
            val entity = entities[index]

            if (uidFromInsert != -1L) {
                entity.uid = uidFromInsert
            } else {
                val subscriptionIdFromDb = getSubscriptionIdInternal(entity.serviceId, entity.url)
                    ?: throw IllegalStateException("Subscription cannot be null just after insertion.")
                entity.uid = subscriptionIdFromDb

                update(entity)
            }
        }

        return entities
    }
}
