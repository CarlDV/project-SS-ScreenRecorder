package dev.screenrec.output

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.time.ZoneId

/**
 * Writes into the Gallery the way the platform wants: insert a row flagged IS_PENDING so
 * nothing half-written is visible, hand the muxer that row's file descriptor, then clear
 * the flag. A failure deletes the row rather than leaving a zero-byte item behind.
 */
class MediaStoreOutput(private val context: Context) {

    data class PendingRecording(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val displayName: String
    )

    fun createPending(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): PendingRecording {
        val name = RecordingFilename.forEpochMillis(nowMillis, zone)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.DATE_ADDED, nowMillis / 1000)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, values)) {
            "MediaStore refused to create $name"
        }
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw")) {
            "MediaStore returned no descriptor for $uri"
        }
        return PendingRecording(uri, descriptor, name)
    }

    /** Clears IS_PENDING, making the recording visible in the Gallery. */
    fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun discard(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not delete pending row $uri", e)
        }
    }

    /**
     * If the process was killed mid-recording, its row is still pending and invisible.
     * Called at launch so those never accumulate.
     */
    fun cleanUpOrphans() {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$RELATIVE_PATH%")
        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    discard(ContentUris.withAppendedId(collection, c.getLong(idColumn)))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not scan for orphaned pending rows", e)
        }
    }

    private companion object {
        const val TAG = "MediaStoreOutput"
        const val MIME_TYPE = "video/mp4"

        /** Samsung's own location, so recordings land where users already look. */
        const val RELATIVE_PATH = "DCIM/Screen recordings"
    }
}
