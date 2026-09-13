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
        return toCanonicalPath(null, pathOrUri)
    }

    /**
     * Converts any audio path or URI (SAF content URI, MediaStore content URI, file:// URI, or raw path)
     * to a canonical, decoded Linux filesystem path.
     */
    fun toCanonicalPath(context: Context?, pathOrUri: String): String {
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

        if (clean.startsWith("content://media/") && context != null) {
            val resolved = resolveMediaStoreToCanonicalPath(context, clean)
            if (!resolved.isNullOrBlank()) {
                return normalizePath(resolved)
            }
        }

        return clean
    }

    /**
     * Resolves a MediaStore audio content URI to its underlying Linux filesystem path.
     */
    fun resolveMediaStoreToCanonicalPath(context: Context, uriStr: String): String? {
        try {
            val uri = Uri.parse(uriStr)
            val proj = mutableListOf(
                android.provider.MediaStore.Audio.Media.DATA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                proj.add(android.provider.MediaStore.Audio.Media.RELATIVE_PATH)
                proj.add(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
                proj.add(android.provider.MediaStore.Audio.Media.VOLUME_NAME)
            }
            context.contentResolver.query(uri, proj.toTypedArray(), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val dataIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                    if (dataIdx != -1) {
                        val data = c.getString(dataIdx)
                        if (!data.isNullOrBlank() && data.startsWith("/")) {
                            return data
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.RELATIVE_PATH)
                        val dnIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
                        val volIdx = c.getColumnIndex(android.provider.MediaStore.Audio.Media.VOLUME_NAME)
                        val rel = if (relIdx != -1) c.getString(relIdx) else null
                        val dn = if (dnIdx != -1) c.getString(dnIdx) else null
                        val vol = if (volIdx != -1) c.getString(volIdx) else null
                        if (!dn.isNullOrBlank()) {
                            val root = when {
                                vol == null || vol == "external_primary" || vol == "external" -> PRIMARY_EMULATED_ROOT
                                vol.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "/storage/$vol"
                                else -> PRIMARY_EMULATED_ROOT
                            }
                            val cleanRel = (rel ?: "").trim('/')
                            return if (cleanRel.isNotBlank()) "$root/$cleanRel/$dn" else "$root/$dn"
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Extracts a clean, storage-relative path (e.g. "Music/Artist - Song.mp3")
     * from any URI or filesystem path.
     */
    fun toStorageRelativePath(pathOrUri: String): String {
        return toStorageRelativePath(null, pathOrUri)
    }

    fun toStorageRelativePath(context: Context?, pathOrUri: String): String {
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
        val canonical = toCanonicalPath(context, clean)
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
        return isSamePhysicalFile(null, pathOrUriA, pathOrUriB)
    }

    fun isSamePhysicalFile(context: Context?, pathOrUriA: String, pathOrUriB: String): Boolean {
        if (pathOrUriA.isBlank() || pathOrUriB.isBlank()) return false
        if (pathOrUriA == pathOrUriB) return true

        // Compare physical media keys if available
        val keyA = PhysicalMediaIdentifier.computePhysicalMediaKey(context, pathOrUriA)
        val keyB = PhysicalMediaIdentifier.computePhysicalMediaKey(context, pathOrUriB)
        if (keyA.isNotBlank() && keyB.isNotBlank() && keyA == keyB) {
            return true
        }

        val canA = toCanonicalPath(context, pathOrUriA)
        val canB = toCanonicalPath(context, pathOrUriB)
        if (canA.isNotBlank() && canB.isNotBlank() && !canA.startsWith("content://") && !canB.startsWith("content://") && canA.equals(canB, ignoreCase = true)) {
            return true
        }

        val relA = toStorageRelativePath(context, pathOrUriA)
        val relB = toStorageRelativePath(context, pathOrUriB)
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
        if (pathOrUri.startsWith("content://media/")) {
            val vol = PhysicalMediaIdentifier.extractVolumeFromUri(pathOrUri)?.lowercase(Locale.ROOT)
            return vol == null || vol == "external" || vol == "external_primary" || vol == "internal"
        }
        val p = toCanonicalPath(pathOrUri)
        return p.startsWith(PRIMARY_EMULATED_ROOT, ignoreCase = true) || p.contains("/storage/emulated/")
    }

    /**
     * Extracts canonical storage volume identifier (e.g. "primary" or "aa44-8296").
     */
    fun extractStorageVolume(pathOrUri: String): String {
        if (pathOrUri.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY")) {
            val docId = extractDocumentIdFromUri(pathOrUri) ?: ""
            val rawVol = if (docId.contains(':')) docId.substringBefore(':') else "primary"
            return if (rawVol.equals("primary", ignoreCase = true)) "primary" else rawVol.lowercase(Locale.ROOT)
        }
        if (pathOrUri.startsWith("content://media/")) {
            val vol = PhysicalMediaIdentifier.extractVolumeFromUri(pathOrUri)?.lowercase(Locale.ROOT)
            return if (vol == null || vol == "external" || vol == "external_primary" || vol == "internal") "primary" else vol
        }
        val p = toCanonicalPath(pathOrUri)
        return when {
            p.startsWith(PRIMARY_EMULATED_ROOT, ignoreCase = true) || p.contains("/storage/emulated/") -> "primary"
            p.startsWith("/storage/") -> {
                val sub = p.removePrefix("/storage/")
                val vol = sub.substringBefore('/')
                if (vol.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) vol.lowercase(Locale.ROOT) else "primary"
            }
            else -> "primary"
        }
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
