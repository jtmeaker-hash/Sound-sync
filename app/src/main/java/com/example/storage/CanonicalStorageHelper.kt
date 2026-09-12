package com.example.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URLDecoder
import java.util.Locale

/**
 * Robust canonical path and URI reconciliation utility.
 *
 * Bridges the gap between Android Scoped Storage (SAF document URIs),
 * MediaStore content URIs, and direct Linux filesystem paths (/storage/emulated/0/...).
 *
 * Ensures SoundSync treats physical files consistently regardless of whether
 * they are enumerated via SAF directory trees or direct filesystem APIs.
 */
object CanonicalStorageHelper {

    private const val TAG = "CanonicalStorageHelper"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_EMULATED_ROOT = "/storage/emulated/0"

    /**
     * Converts any audio path or URI (SAF content URI, file:// URI, or raw path)
     * to a canonical, decoded Linux filesystem path.
     *
     * Examples:
     *  - content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FArtist%20-%20Song.mp3
     *    -> /storage/emulated/0/Music/Artist - Song.mp3
     *  - content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.mp3
     *    -> /storage/emulated/0/Music/song.mp3
     *  - file:///storage/emulated/0/Music/song.mp3
     *    -> /storage/emulated/0/Music/song.mp3
     *  - /storage/emulated/0/Music/song.mp3
     *    -> /storage/emulated/0/Music/song.mp3
     */
    fun toCanonicalPath(pathOrUri: String): String {
        if (pathOrUri.isBlank() || pathOrUri.startsWith("demo://") || pathOrUri.startsWith("http")) {
            return pathOrUri
        }

        val clean = pathOrUri.trim()

        if (clean.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY")) {
            return parseExternalStorageSafUri(clean)
        }

        if (clean.startsWith("file://")) {
            val decoded = safeUrlDecode(clean.removePrefix("file://"))
            return normalizePath(decoded)
        }

        if (clean.startsWith("/")) {
            return normalizePath(clean)
        }

        return clean
    }

    /**
     * Extracts a clean, storage-relative path (e.g. "Music/Artist - Song.mp3")
     * from any URI or filesystem path.
     */
    fun toStorageRelativePath(pathOrUri: String): String {
        if (pathOrUri.isBlank() || pathOrUri.startsWith("demo://") || pathOrUri.startsWith("http")) {
            return ""
        }

        val clean = pathOrUri.trim()

        // Case 1: SAF URI with primary: or volume: document ID
        if (clean.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY")) {
            val docId = extractDocumentIdFromUri(clean)
            if (docId != null) {
                return when {
                    docId.startsWith("primary:", ignoreCase = true) ->
                        docId.substringAfter(':').trimStart('/')
                    docId.contains(':') ->
                        docId.substringAfter(':').trimStart('/')
                    else -> docId.trimStart('/')
                }
            }
        }

        // Case 2: Canonical or direct path
        val canonical = toCanonicalPath(clean)
        if (canonical.startsWith("content://")) {
            return ""
        }
        return when {
            canonical.startsWith(PRIMARY_EMULATED_ROOT, ignoreCase = true) ->
                canonical.substring(PRIMARY_EMULATED_ROOT.length).trimStart('/')
            canonical.matches(Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}(/.*)?$")) ->
                canonical.substringAfter("/storage/").substringAfter('/').trimStart('/')
            canonical.startsWith("/") ->
                canonical.trimStart('/')
            else -> canonical
        }
    }

    /**
     * Returns true if two path/URI representations point to the exact same physical file
     * on local internal or external storage.
     */
    fun isSamePhysicalFile(pathOrUriA: String, pathOrUriB: String): Boolean {
        if (pathOrUriA.isBlank() || pathOrUriB.isBlank()) return false
        if (pathOrUriA == pathOrUriB) return true

        val canA = toCanonicalPath(pathOrUriA)
        val canB = toCanonicalPath(pathOrUriB)
        if (canA.isNotBlank() && canB.isNotBlank() && canA.equals(canB, ignoreCase = true)) {
            return true
        }

        val relA = toStorageRelativePath(pathOrUriA)
        val relB = toStorageRelativePath(pathOrUriB)
        if (relA.isNotBlank() && relB.isNotBlank() && relA.equals(relB, ignoreCase = true)) {
            val isIntA = isInternalOrPrimary(pathOrUriA)
            val isIntB = isInternalOrPrimary(pathOrUriB)
            if (isIntA && isIntB) return true
        }

        return false
    }

    /**
     * Checks whether the path or URI refers to internal / emulated primary storage
     * (/storage/emulated/0 or primary: SAF URI).
     */
    fun isInternalOrPrimary(pathOrUri: String): Boolean {
        if (pathOrUri.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY")) {
            val docId = extractDocumentIdFromUri(pathOrUri) ?: ""
            return docId.startsWith("primary:", ignoreCase = true) || pathOrUri.contains("primary%3A", ignoreCase = true)
        }
        val p = toCanonicalPath(pathOrUri)
        return p.startsWith(PRIMARY_EMULATED_ROOT, ignoreCase = true) || p.contains("/storage/emulated/")
    }

    /**
     * Extracts the display file name (including extension) from any path or URI.
     */
    fun extractFileName(pathOrUri: String): String {
        if (pathOrUri.isBlank()) return ""
        val decoded = safeUrlDecode(pathOrUri).substringBefore('?').substringBefore('#')
        if (decoded.startsWith("content://media/") && decoded.substringAfterLast('/').toLongOrNull() != null) {
            return ""
        }
        val lastSlash = decoded.lastIndexOf('/')
        val lastColon = decoded.lastIndexOf(':')
        val splitIdx = maxOf(lastSlash, lastColon)
        return if (splitIdx >= 0 && splitIdx < decoded.length - 1) {
            decoded.substring(splitIdx + 1)
        } else {
            decoded
        }
    }

    /**
     * Attempts to find a readable, accessible path or URI for a stored track reference.
     * Tests:
     *  1. Direct read on current path
     *  2. Direct read on canonical path
     *  3. SAF tree lookup if granted
     */
    fun resolvePlayableReference(context: Context, pathOrUri: String): String? {
        if (pathOrUri.isBlank() || pathOrUri.startsWith("demo://")) return pathOrUri

        // 1. Direct test
        if (isReferenceReadable(context, pathOrUri)) {
            return pathOrUri
        }

        // 2. Canonical filesystem path test
        val canonical = toCanonicalPath(pathOrUri)
        if (canonical != pathOrUri && isReferenceReadable(context, canonical)) {
            return canonical
        }

        // 3. MediaStore lookup for raw path
        if (!pathOrUri.startsWith("content://")) {
            val mediaStoreUri = TrackSourceResolver.findMediaStoreUriForPath(context, canonical)
            if (mediaStoreUri != null && isReferenceReadable(context, mediaStoreUri.toString())) {
                return mediaStoreUri.toString()
            }
        }

        // 4. Reverse lookup: If given a filesystem path, can we find an accessible SAF URI?
        if (!pathOrUri.startsWith("content://")) {
            val safUri = findAccessibleSafUriForPath(context, canonical)
            if (safUri != null && isReferenceReadable(context, safUri)) {
                return safUri
            }
        }

        return null
    }

    /**
     * Given a canonical filesystem path, attempts to construct or find an accessible SAF URI
     * within the app's persisted SAF directory grants.
     */
    fun findAccessibleSafUriForPath(context: Context, canonicalPath: String): String? {
        val relPath = toStorageRelativePath(canonicalPath)
        if (relPath.isBlank()) return null

        val persistedTrees = SafStorageManager.getPersistedAccessibleFolderUris(context)
        for (treeUri in persistedTrees) {
            val rootDoc = try { DocumentFile.fromTreeUri(context, treeUri) } catch (_: Exception) { null }
            if (rootDoc == null || !rootDoc.exists() || !rootDoc.canRead()) continue

            val doc = SafStorageManager.findDocumentByRelativePath(rootDoc, relPath)
            if (doc != null && doc.exists() && doc.canRead()) {
                return doc.uri.toString()
            }
        }
        return null
    }

    /**
     * Checks whether a URI or file path can be opened and read.
     */
    fun isReferenceReadable(context: Context, pathOrUri: String): Boolean {
        if (pathOrUri.isBlank()) return false
        if (pathOrUri.startsWith("demo://")) return true

        if (pathOrUri.startsWith("content://")) {
            return try {
                val uri = Uri.parse(pathOrUri)
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true }
                    ?: context.contentResolver.openInputStream(uri)?.use { true }
                    ?: false
            } catch (_: Exception) {
                false
            }
        }

        val cleanPath = pathOrUri.removePrefix("file://")
        return try {
            val f = File(cleanPath)
            TrackSourceResolver.isGenuinelyRawReadable(f)
        } catch (_: Exception) {
            false
        }
    }

    // ── Internal Helpers ────────────────────────────────────────────────────

    private fun parseExternalStorageSafUri(uriString: String): String {
        val docId = extractDocumentIdFromUri(uriString) ?: return uriString

        return when {
            docId.startsWith("primary:", ignoreCase = true) -> {
                val subPath = docId.substringAfter(':').trimStart('/')
                normalizePath("$PRIMARY_EMULATED_ROOT/$subPath")
            }
            docId.contains(':') -> {
                val volume = docId.substringBefore(':')
                val subPath = docId.substringAfter(':').trimStart('/')
                normalizePath("/storage/$volume/$subPath")
            }
            else -> normalizePath("$PRIMARY_EMULATED_ROOT/$docId")
        }
    }

    private fun extractDocumentIdFromUri(uriString: String): String? {
        try {
            val uri = Uri.parse(uriString)
            // If it's a document URI, use DocumentsContract or parse path segments
            if (DocumentsContract.isDocumentUri(null, uri)) {
                try {
                    val docId = DocumentsContract.getDocumentId(uri)
                    if (!docId.isNullOrBlank()) return docId
                } catch (_: Exception) {}
            }

            val segments = uri.pathSegments
            val docIdx = segments.indexOf("document")
            if (docIdx != -1 && docIdx + 1 < segments.size) {
                return safeUrlDecode(segments[docIdx + 1])
            }

            val treeIdx = segments.indexOf("tree")
            if (treeIdx != -1 && treeIdx + 1 < segments.size) {
                return safeUrlDecode(segments[treeIdx + 1])
            }
        } catch (_: Exception) {}

        // Regex fallback
        val docMatch = Regex("""/document/([^/?#]+)""").find(uriString)
        if (docMatch != null) {
            return safeUrlDecode(docMatch.groupValues[1])
        }

        val treeMatch = Regex("""/tree/([^/?#]+)""").find(uriString)
        if (treeMatch != null) {
            return safeUrlDecode(treeMatch.groupValues[1])
        }

        return null
    }

    private fun safeUrlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    private fun normalizePath(path: String): String {
        var p = path.replace('\\', '/')
        while (p.contains("//")) {
            p = p.replace("//", "/")
        }
        return if (p.length > 1 && p.endsWith('/')) p.dropLast(1) else p
    }
}
