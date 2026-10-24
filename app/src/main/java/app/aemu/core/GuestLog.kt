package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Журнал гостя. Гостевой liblog пишет в /dev/log/main (5.0+: logd, в /dev/log/<buffer>) записи вида
 *   [приоритет:1 байт][тег]\0[сообщение]\0
 * (заголовок добавило бы ядро, но драйвера logger нет — файл обычный).
 *
 * LogdSink пишет перед каждой записью метку 0x1e и время в наносекундах (8 байт): без неё
 * невозможно свести main и system в один журнал по порядку. Записи старого формата (crash.c,
 * гости до logd) метки не имеют и читаются как раньше.
 */
object GuestLog {
    data class Rec(val prio: Char, val tag: String, val msg: String, val buf: String = "main", val timeNs: Long = 0)

    private const val TIME = 0x1e

    fun tail(root: File, bytes: Int = 256 * 1024, buffer: String = "main"): List<Rec> {
        val f = File(root, "dev/log/$buffer")
        if (!f.isFile || f.length() == 0L) return emptyList()
        val data = runCatching {
            RandomAccessFile(f, "r").use { r ->
                val n = minOf(r.length(), bytes.toLong()).toInt()
                r.seek(r.length() - n)
                ByteArray(n).also { r.readFully(it) }
            }
        }.getOrNull() ?: return emptyList()
        return parse(data, buffer)
    }

    /**
     * main + system + radio + crash. Records written by the logd sink carry a timestamp, so the
     * buffers are merged in real order; old records (no timestamp) go first in their file order.
     * system is where framework messages live -- including Watchdog's reason for killing system_server.
     */
    fun tailAll(root: File, bytes: Int = 256 * 1024,
                buffers: List<String> = listOf("main", "system", "radio", "crash")): List<Rec> {
        val all = ArrayList<Rec>()
        for (b in buffers) all.addAll(tail(root, bytes, b))
        all.sortWith(compareBy<Rec> { if (it.timeNs == 0L) 0 else 1 }.thenBy { it.timeNs })
        return all
    }

    fun parse(data: ByteArray, buffer: String = "main"): List<Rec> {
        val out = ArrayList<Rec>()
        var i = 0
        while (i < data.size - 2) {
            val start = i
            var p = data[i].toInt() and 0xff
            var time = 0L
            if (p == TIME && i + 10 < data.size) {
                var t = 0L
                for (k in 0 until 8) t = t or ((data[i + 1 + k].toLong() and 0xff) shl (8 * k))
                val np = data[i + 9].toInt() and 0xff
                if (np in 2..8) { time = t; i += 9; p = np }
            }
            val end = indexOf(data, i + 1)
            if (end < 0) break
            if (p in 2..8 && end - i - 1 in 1..48) {
                val tag = String(data, i + 1, end - i - 1, Charsets.UTF_8)
                if (tag.none { it < ' ' }) {
                    val j = end + 1
                    var end2 = indexOf(data, j)
                    if (end2 < 0) end2 = data.size
                    out.add(Rec("??VDIWEFS"[p], tag, String(data, j, end2 - j, Charsets.UTF_8), buffer, time))
                    i = end2 + 1
                    continue
                }
            }
            i = start + 1
        }
        return out
    }

    fun contains(root: File, vararg needles: String): Boolean {
        val t = listOf("main", "system", "radio", "crash").joinToString("\n") { n ->
            val f = File(root, "dev/log/$n")
            if (!f.isFile) "" else runCatching { String(f.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
        }
        return needles.any { t.contains(it) }
    }

    fun clear(root: File) {
        for (n in listOf("main", "system", "radio", "events", "crash")) {
            val f = File(root, "dev/log/$n")
            if (f.isFile) runCatching { RandomAccessFile(f, "rw").use { it.setLength(0) } }
        }
    }

    private fun indexOf(a: ByteArray, from: Int): Int {
        for (k in from until a.size) if (a[k] == 0.toByte()) return k
        return -1
    }
}
