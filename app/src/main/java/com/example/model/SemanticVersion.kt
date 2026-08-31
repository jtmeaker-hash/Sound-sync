package com.example.model

/**
 * Robust Semantic Version model and comparator adhering to SemVer 2.0.0.
 *
 * Supports formats like:
 * - "1.0.0"
 * - "v1.2.3"
 * - "1.0.1-beta.1"
 * - "2.0.0-rc.2+20260830"
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val buildMetadata: String? = null,
    val rawString: String = ""
) : Comparable<SemanticVersion> {

    companion object {
        private val SEMVER_REGEX = Regex(
            """^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?${'$'}"""
        )

        /**
         * Parses a version string into a [SemanticVersion].
         * Falls back to a safe 0.0.0 version if unparseable.
         */
        fun parse(versionStr: String?): SemanticVersion {
            if (versionStr.isNullOrBlank()) {
                return SemanticVersion(0, 0, 0, rawString = "")
            }

            val clean = versionStr.trim()
            val match = SEMVER_REGEX.matchEntire(clean)

            return if (match != null) {
                val (majorStr, minorStr, patchStr, pre, build) = match.destructured
                SemanticVersion(
                    major = majorStr.toIntOrNull() ?: 0,
                    minor = minorStr.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0,
                    patch = patchStr.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0,
                    preRelease = pre.takeIf { it.isNotEmpty() },
                    buildMetadata = build.takeIf { it.isNotEmpty() },
                    rawString = clean
                )
            } else {
                // Fallback: extract leading numbers
                val numbers = Regex("""\d+""").findAll(clean).map { it.value.toIntOrNull() ?: 0 }.toList()
                SemanticVersion(
                    major = numbers.getOrElse(0) { 0 },
                    minor = numbers.getOrElse(1) { 0 },
                    patch = numbers.getOrElse(2) { 0 },
                    preRelease = null,
                    buildMetadata = null,
                    rawString = clean
                )
            }
        }
    }

    val displayString: String
        get() = buildString {
            append("$major.$minor.$patch")
            if (!preRelease.isNullOrBlank()) {
                append("-$preRelease")
            }
        }

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)

        // Pre-release versions have lower precedence than normal versions
        // 1.0.0-alpha < 1.0.0
        if (preRelease == null && other.preRelease != null) return 1
        if (preRelease != null && other.preRelease == null) return -1
        if (preRelease != null && other.preRelease != null) {
            return comparePreReleases(preRelease, other.preRelease)
        }

        return 0
    }

    private fun comparePreReleases(preA: String, preB: String): Int {
        val partsA = preA.split(".")
        val partsB = preB.split(".")
        val maxLen = maxOf(partsA.size, partsB.size)

        for (i in 0 until maxLen) {
            val a = partsA.getOrNull(i)
            val b = partsB.getOrNull(i)
            if (a == null) return -1
            if (b == null) return 1

            val numA = a.toIntOrNull()
            val numB = b.toIntOrNull()

            if (numA != null && numB != null) {
                if (numA != numB) return numA.compareTo(numB)
            } else if (numA != null) {
                return -1 // Numeric identifier always has lower precedence than string
            } else if (numB != null) {
                return 1
            } else {
                val strCompare = a.compareTo(b)
                if (strCompare != 0) return strCompare
            }
        }
        return 0
    }

    fun isNewerThan(other: SemanticVersion): Boolean = this > other

    fun isNewerThan(versionString: String): Boolean = this > parse(versionString)
}
