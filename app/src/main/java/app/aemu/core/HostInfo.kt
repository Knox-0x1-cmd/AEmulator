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
        meminfoMb("MemTotal:") ?: 8192L
    }

    /**
     * Free RAM in MB right now (MemAvailable, which includes reclaimable page cache). Unlike [ramMb] this is
     * not cached: the settings sheet warns from it, and the value changes with every app the phone starts.
     */
    fun availableRamMb(): Long = meminfoMb("MemAvailable:") ?: ramMb

    /** What the guest can take without pushing the phone into swap: free RAM minus a reserve for the host. */
    fun usableRamMb(): Long = (availableRamMb() - RAM_RESERVE_MB).coerceAtLeast(0)

    /** Kept for the Android UI itself; below this the guest competes with system_server and gets killed. */
    const val RAM_RESERVE_MB = 512L

    private fun meminfoMb(key: String): Long? = runCatching {
        var kb = -1L
        java.io.File("/proc/meminfo").forEachLine { line ->
            if (kb < 0 && line.startsWith(key)) {
                kb = line.substringAfter(key).trim().substringBefore(' ').toLong()
            }
        }
        if (kb >= 0) kb / 1024 else null
    }.getOrNull()
}
