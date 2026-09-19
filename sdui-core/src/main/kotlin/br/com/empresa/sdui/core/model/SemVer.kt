package br.com.empresa.sdui.core.model

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "semver components must be >= 0" }
    }

    val ordinal: Long get() = major * 1_000_000L + minor * 1_000L + patch

    val majorMinor: String get() = "$major.$minor"

    override fun compareTo(other: SemVer): Int = ordinal.compareTo(other.ordinal)

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
                return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
            TWO.matchEntire(value)?.let { m ->
                return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 0)
            }
            ONE.matchEntire(value)?.let { m ->
                return SemVer(m.groupValues[1].toInt(), 0, 0)
            }
            return null
        }

        fun parseThreePart(raw: String?): SemVer? {
            val value = raw?.trim().orEmpty()
            val match = THREE.matchEntire(value) ?: return null
            return SemVer(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
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
