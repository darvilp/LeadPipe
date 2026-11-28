package org.schabi.newpipe.database.subscription

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subscription_memberships",
    primaryKeys = ["subscription_id", "origin"],
    foreignKeys = [ForeignKey(
        entity = SubscriptionEntity::class,
        parentColumns = ["uid"],
        childColumns = ["subscription_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("origin")]
)
data class SubscriptionMembership(
    @ColumnInfo(name = "subscription_id") val subscriptionId: Long,
    val origin: String
) {
    companion object {
        // Local ownership follows a database restore. On the wire it uses the install UUID.
        const val LOCAL = "local"
    }
}

@Entity(tableName = "subscription_sync_revisions")
data class SubscriptionSyncRevision(@PrimaryKey val origin: String, val revision: Long)

data class SubscriptionOwnership(
    @Embedded val subscription: SubscriptionEntity,
    @ColumnInfo(name = "locally_owned") val locallyOwned: Boolean
)
