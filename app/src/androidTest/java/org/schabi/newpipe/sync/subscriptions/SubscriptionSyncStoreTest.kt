package org.schabi.newpipe.sync.subscriptions

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.Migrations.MIGRATION_903_904
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.database.subscription.SubscriptionMembership
import org.schabi.newpipe.extractor.subscription.SubscriptionItem
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class SubscriptionSyncStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val origin = "7240d611-efae-46ef-bf8e-d490724ed068"
    private val anotherOrigin = "15632b8d-ecd6-4149-97b3-f1504f24aa42"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun cleanup() {
        context.deleteDatabase("subscription-sync-migration-test.db")
    }

    @Test
    fun migrate903PreservesLocalMembership() {
        val name = "subscription-sync-migration-test.db"
        helper.createDatabase(name, 903).use {
            it.execSQL("INSERT INTO subscriptions (uid, service_id, url, name, notification_mode) "
                + "VALUES (1, 0, 'https://www.youtube.com/@local', 'Local', 1)")
        }
        helper.runMigrationsAndValidate(name, 904, true, MIGRATION_903_904).use { db ->
            db.query("SELECT subscription_id, origin FROM subscription_memberships").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals(SubscriptionMembership.LOCAL, it.getString(1))
                assertFalse(it.moveToNext())
            }
        }
    }

    @Test
    fun removalsAndPurgePreserveLocalAndOverlappingSubscriptions() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.subscriptionDAO()
            val store = SubscriptionSyncStore(db)
            val shared = channel("shared")
            val adopted = channel("adopted")
            val remote = channel("remote")
            val local = channel("local")
            val sharedId = dao.insert(SubscriptionEntity.from(SubscriptionItem(0, shared.url, shared.name)))
            val localId = dao.insert(SubscriptionEntity.from(SubscriptionItem(0, local.url, local.name)))
            dao.addMembership(SubscriptionMembership(sharedId, SubscriptionMembership.LOCAL))
            dao.addMembership(SubscriptionMembership(localId, SubscriptionMembership.LOCAL))

            assertTrue(store.apply(SubscriptionSnapshot(origin, 2, listOf(shared, remote, adopted))))
            assertTrue(store.apply(SubscriptionSnapshot(anotherOrigin, 1, listOf(remote))))
            assertFalse(store.apply(SubscriptionSnapshot(origin, 1, emptyList())))
            assertFalse(store.apply(SubscriptionSnapshot(origin, 2, emptyList())))
            assertEquals(setOf(shared.url, local.url), dao.localSubscriptions().map { it.url }.toSet())
            val remoteId = dao.exactId(0, remote.url)!!
            val adoptedId = dao.exactId(0, adopted.url)!!
            dao.addMembership(SubscriptionMembership(adoptedId, SubscriptionMembership.LOCAL))

            assertTrue(store.apply(SubscriptionSnapshot(origin, 3, emptyList())))
            assertEquals(remoteId, dao.exactId(0, remote.url))
            assertEquals(sharedId, dao.exactId(0, shared.url))
            assertEquals(adoptedId, dao.exactId(0, adopted.url))

            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO feed_group (uid, name, icon_id, sort_order) VALUES (1, 'Group', 0, 0)"
            )
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO feed_group_subscription_join (group_id, subscription_id) VALUES (1, ?)",
                arrayOf(remoteId)
            )
            assertEquals(1, store.purge())
            assertNull(dao.exactId(0, remote.url))
            assertEquals(-1L, dao.acceptedRevision(anotherOrigin))
            assertEquals(setOf(shared.url, adopted.url, local.url), dao.localSubscriptions().map { it.url }.toSet())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM feed_group_subscription_join").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            assertTrue(store.apply(SubscriptionSnapshot(anotherOrigin, 1, listOf(remote))))
        } finally {
            db.close()
        }
    }

    @Test
    fun purgeRejectsOlderNasFallbackAndInvalidatesPendingPull() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "subscription-sync-test").toFile()
        val isolatedContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.subscriptionDAO()
            val store = SubscriptionSyncStore(db)
            val removed = channel("removed")
            val kept = channel("kept")
            val revision7 = SubscriptionSnapshot(origin, 7, listOf(kept, removed))
            val revision8 = SubscriptionSnapshot(origin, 8, listOf(kept))

            SubscriptionSync.enable(isolatedContext)
            val pendingPull = SubscriptionSync.session(isolatedContext)
            assertTrue(SubscriptionSync.apply(isolatedContext, pendingPull, revision7, store = store))
            assertTrue(SubscriptionSync.apply(isolatedContext, pendingPull, revision8, store = store))
            assertNull(dao.exactId(0, removed.url))

            assertEquals(1, SubscriptionSync.purge(isolatedContext, store))
            assertEquals(0, SubscriptionSync.purge(isolatedContext, store))
            SubscriptionSync.enable(isolatedContext)
            val resumed = SubscriptionSync.session(isolatedContext)

            // Revision 8 is unavailable in the folder and the reader falls back to revision 7.
            assertFalse(SubscriptionSync.apply(isolatedContext, resumed, revision7, store = store))
            // Even the newest snapshot from a pull that started before the purge is stale.
            assertFalse(SubscriptionSync.apply(isolatedContext, pendingPull, revision8, store = store))
            assertFalse(SubscriptionSync.apply(isolatedContext, pendingPull, revision7.copy(revision = 9), store = store))
            assertNull(dao.exactId(0, kept.url))
            assertNull(dao.exactId(0, removed.url))

            assertTrue(SubscriptionSync.apply(isolatedContext, resumed, revision8, store = store))
            assertEquals(listOf(dao.exactId(0, kept.url)!!), dao.memberIds(origin))
            assertNull(dao.exactId(0, removed.url))
            assertFalse(SubscriptionSync.apply(isolatedContext, resumed, revision8, store = store))
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    private fun channel(handle: String) = SnapshotChannel("youtube", "https://www.youtube.com/@$handle", handle)
}
