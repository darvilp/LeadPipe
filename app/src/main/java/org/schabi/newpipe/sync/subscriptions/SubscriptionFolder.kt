package org.schabi.newpipe.sync.subscriptions

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

internal object SubscriptionFolder {
    private val fileName = Regex("""leadpipe-subs-v1-([0-9a-f-]{36})-([1-9][0-9]*)\.json""")
    private data class Candidate(val uri: Uri, val origin: String, val revision: Long)

    data class Result(val applied: Int, val skipped: Int, val unreadable: Int)

    fun sync(context: Context, session: SyncSession, uri: Uri): Result {
        val folder = DocumentFile.fromTreeUri(context, uri)
        check(folder?.isDirectory == true && folder.canRead() && folder.canWrite()) {
            "Choose a shared directory with read and write access."
        }

        val outgoing = SubscriptionSync.outgoing(context, session)
        val name = "leadpipe-subs-v1-${outgoing.snapshot.origin}-${outgoing.snapshot.revision}.json"
        val document = folder.createFile("application/json", name) ?: throw IOException("Cannot create snapshot")
        try {
            check(document.name == name) { "The storage provider changed the snapshot filename" }
            val output = context.contentResolver.openOutputStream(document.uri, "w")
                ?: throw IOException("Cannot open snapshot for writing")
            output.use { it.write(outgoing.snapshot.encode().toByteArray(Charsets.UTF_8)) }
        } catch (error: Exception) {
            runCatching { document.delete() }
            throw error
        }

        val candidates = candidates(context, uri).groupBy { it.origin }

        var applied = 0
        var unreadable = 0
        for ((origin, versions) in candidates) {
            val newestFirst = versions.sortedByDescending { it.revision }
            if (origin == session.deviceId) {
                val written = newestFirst.firstOrNull { it.revision == outgoing.snapshot.revision }
                if (written != null && read(context, written) != null) {
                    // Keep a complete predecessor, even if a newer file was interrupted.
                    val previous = newestFirst.firstOrNull { it.revision < written.revision && read(context, it) != null }
                    if (previous != null) newestFirst.filter { it.revision < previous.revision }.forEach {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) }
                    }
                }
                continue
            }

            val snapshot = newestFirst.firstNotNullOfOrNull {
                read(context, it).also { result -> if (result == null) unreadable++ }
            } ?: continue
            if (SubscriptionSync.apply(context, session, snapshot)) applied++
        }
        return Result(applied, outgoing.skipped, unreadable)
    }

    private fun candidates(context: Context, tree: Uri): List<Candidate> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val cursor = context.contentResolver.query(children, projection, null, null, null)
            ?: throw IOException("Cannot list snapshots in the shared directory")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val match = fileName.matchEntire(it.getString(1).orEmpty()) ?: continue
                    val origin = match.groupValues[1]
                    if (!SubscriptionSnapshot.validOrigin(origin)) continue
                    val revision = match.groupValues[2].toLongOrNull() ?: continue
                    val document = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                    add(Candidate(document, origin, revision))
                }
            }
        }
    }

    private fun read(context: Context, candidate: Candidate): SubscriptionSnapshot? = runCatching {
        val input = context.contentResolver.openInputStream(candidate.uri)
            ?: throw IOException("Cannot open snapshot")
        val snapshot = input.use(SubscriptionSnapshot::read)
        require(snapshot.origin == candidate.origin && snapshot.revision == candidate.revision)
        snapshot
    }.getOrNull()
}
