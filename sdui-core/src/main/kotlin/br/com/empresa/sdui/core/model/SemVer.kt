package br.com.empresa.sdui.core.model

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "semver components must be >= 0" }
    }

    val ordinal: Long get() = major.toLong() * 1_000_000_000_000L + minor.toLong() * 1_000_000L + patch.toLong()

    val majorMinor: String get() = "$major.$minor"

    override fun compareTo(other: SemVer): Int {
        val c1 = major.compareTo(other.major)
        if (c1 != 0) return c1
        val c2 = minor.compareTo(other.minor)
        if (c2 != 0) return c2
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    fun toOsString(): String = if (patch == 0) "$major.$minor" else toString()

    companion object {
        private val THREE = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
        private val TWO = Regex("""^(\d+)\.(\d+)$""")
        private val ONE = Regex("""^(\d+)$""")

        fun parse(raw: String?): SemVer? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            THREE.matchEntire(value)?.let { m ->
                val major = m.groupValues[1].toIntOrNull() ?: return null
                val minor = m.groupValues[2].toIntOrNull() ?: return null
                val patch = m.groupValues[3].toIntOrNull() ?: return null
                return SemVer(major, minor, patch)
            }
            TWO.matchEntire(value)?.let { m ->
                val major = m.groupValues[1].toIntOrNull() ?: return null
                val minor = m.groupValues[2].toIntOrNull() ?: return null
                return SemVer(major, minor, 0)
            }
            ONE.matchEntire(value)?.let { m ->
                val major = m.groupValues[1].toIntOrNull() ?: return null
                return SemVer(major, 0, 0)
            }
            return null
        }

        fun parseThreePart(raw: String?): SemVer? {
            val value = raw?.trim().orEmpty()
            val match = THREE.matchEntire(value) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return SemVer(major, minor, patch)
        }
    }
}

data class VersionRange(
    val min: SemVer,
    val max: SemVer?,
) {
    fun contains(version: SemVer): Boolean {
        if (version < min) return false
        val ceiling = max ?: return true
        return version <= ceiling
    }
}
