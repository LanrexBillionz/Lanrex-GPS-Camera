package com.lanrex.sitecam.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lanrex.sitecam.data.StampRepository
import com.lanrex.sitecam.data.db.ItemOrigin
import com.lanrex.sitecam.ui.permissions.AppPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Handles photos/videos shared into SiteCam (for example from Samsung Gallery).
 *
 * A shared file often arrives with its GPS removed. SiteCam therefore looks the
 * file up in MediaStore and reads the original (with location) whenever it can,
 * and only falls back to a private copy for files that are not in MediaStore.
 */
class SharedMediaImporter(
    private val context: Context,
    private val mediaStore: MediaStoreRepository,
    private val repository: StampRepository,
) {
    data class Summary(
        val added: Int = 0,
        val alreadyStamped: Int = 0,
        val alreadyQueued: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
    ) {
        fun message(): String = buildList {
            if (added > 0) add("$added added to the stamping queue")
            if (alreadyQueued > 0) add("$alreadyQueued already in the queue")
            if (alreadyStamped > 0) add("$alreadyStamped already stamped")
            if (skipped > 0) add("$skipped skipped (not a supported photo or video)")
            if (failed > 0) add("$failed could not be read")
        }.joinToString(", ").ifEmpty { "Nothing to stamp." }
    }

    suspend fun import(uris: List<Uri>): Summary = withContext(Dispatchers.IO) {
        var summary = Summary()
        val fullAccess = AppPermissions.snapshot(context).mediaFull
        for (uri in uris.distinct()) {
            summary = try {
                summary + importOne(uri, fullAccess)
            } catch (e: Exception) {
                summary.copy(failed = summary.failed + 1)
            }
        }
        summary
    }

    private suspend fun importOne(uri: Uri, fullAccess: Boolean): StampRepository.AddResult? {
        // 1. A MediaStore item: stamp it from the original.
        val direct = mediaStore.get(uri)
        if (direct != null && fullAccess) {
            if (isOwnOutput(direct.displayName, direct.relativePath)) return null
            return repository.add(direct, ItemOrigin.SHARE)
        }

        val (name, size, mime) = describe(uri)
        val displayName = name ?: direct?.displayName ?: "shared_${System.currentTimeMillis()}"
        val mimeType = mime ?: direct?.mimeType ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (isOwnOutput(displayName, null)) return null
        if (!MediaTypes.isVideo(mimeType) && !MediaTypes.isSupportedPhoto(mimeType, displayName) &&
            !MediaTypes.isRaw(mimeType, displayName)
        ) {
            return null
        }

        // 2. Find the same file in MediaStore by name and size, to read its GPS.
        if (fullAccess && name != null) {
            mediaStore.findByNameAndSize(name, size)?.let { return repository.add(it, ItemOrigin.SHARE) }
        }

        // 3. Not in MediaStore (or no access): keep a private copy to stamp later.
        val dir = File(context.filesDir, "shared").apply { mkdirs() }
        val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val target = File(dir, "${System.currentTimeMillis()}_$safeName")
        val digest = MessageDigest.getInstance("SHA-1")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var hashed = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    if (hashed < HASH_BYTES) {
                        digest.update(buffer, 0, minOf(n.toLong(), HASH_BYTES - hashed).toInt())
                        hashed += n
                    }
                }
            }
        } ?: return null
        digest.update(displayName.toByteArray())
        digest.update(target.length().toString().toByteArray())
        val key = "share:" + digest.digest().joinToString("") { "%02x".format(it) }
        val result = repository.addCopy(
            sourceKey = key,
            sourceUri = uri.toString(),
            localCopyPath = target.absolutePath,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = target.length(),
        )
        if (result != StampRepository.AddResult.ADDED && result != StampRepository.AddResult.RETRYING) target.delete()
        return result
    }

    private fun describe(uri: Uri): Triple<String?, Long?, String?> {
        var name: String? = null
        var size: Long? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
        } catch (e: Exception) {
            // Some apps don't answer this query; the copy still works.
        }
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        return Triple(name, size, mime)
    }

    private fun isOwnOutput(name: String, relativePath: String?): Boolean =
        name.contains("_stamped", ignoreCase = true) ||
            relativePath?.startsWith("Pictures/SiteCam", ignoreCase = true) == true

    private operator fun Summary.plus(result: StampRepository.AddResult?): Summary = when (result) {
        StampRepository.AddResult.ADDED, StampRepository.AddResult.RETRYING -> copy(added = added + 1)
        StampRepository.AddResult.ALREADY_STAMPED -> copy(alreadyStamped = alreadyStamped + 1)
        StampRepository.AddResult.ALREADY_QUEUED -> copy(alreadyQueued = alreadyQueued + 1)
        StampRepository.AddResult.UNSUPPORTED, null -> copy(skipped = skipped + 1)
    }

    private companion object {
        const val HASH_BYTES = 256L * 1024
    }
}
