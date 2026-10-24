package app.aemu.core

/** Сведения о телефоне-хозяине, от которых зависят обходы в движке. */
object HostInfo {
    /** Драйвер GPU (adreno, mali, powervr…) из ro.hardware.egl. */
    fun gpu(): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, "ro.hardware.egl") as String).lowercase()
    }.getOrDefault("")

    /** Total RAM in MB. Read once: several places size the guest by it (8 GB phones get a smaller budget). */
    val ramMb: Long by lazy {
        runCatching {
            java.io.File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemTotal:") } }
                .split(Regex("\\s+"))[1].toLong() / 1024
        }.getOrDefault(8192L)
    }
}
