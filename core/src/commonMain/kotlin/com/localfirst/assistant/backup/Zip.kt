package com.localfirst.assistant.backup

/**
 * The ZIP container used by portable backups. Writing stores entries uncompressed, so every reader can open them;
 * reading follows the central directory (as Java's ZipOutputStream writes sizes after the data) and hands
 * deflated entries to the platform's raw-deflate [Inflate].
 */
fun interface Inflate {
    suspend fun raw(data: ByteArray, expectedSize: Int): ByteArray
}

class ZipEntry(val name: String, val data: ByteArray)

object Zip {
    fun write(entries: List<ZipEntry>): ByteArray {
        val out = Bytes()
        val central = Bytes()
        for (entry in entries) {
            val name = entry.name.encodeToByteArray()
            val crc = Crc32.of(entry.data)
            val offset = out.size
            out.int(0x04034b50); out.short(20); out.short(0x0800); out.short(0); out.short(0); out.short(0x21)
            out.int(crc.toInt()); out.int(entry.data.size); out.int(entry.data.size); out.short(name.size); out.short(0)
            out.bytes(name); out.bytes(entry.data)
            central.int(0x02014b50); central.short(20); central.short(20); central.short(0x0800); central.short(0); central.short(0); central.short(0x21)
            central.int(crc.toInt()); central.int(entry.data.size); central.int(entry.data.size); central.short(name.size)
            central.short(0); central.short(0); central.short(0); central.short(0); central.int(0); central.int(offset); central.bytes(name)
        }
        val start = out.size
        out.bytes(central.toByteArray())
        out.int(0x06054b50); out.short(0); out.short(0); out.short(entries.size); out.short(entries.size)
        out.int(central.size); out.int(start); out.short(0)
        return out.toByteArray()
    }

    /** Reads every entry, rejecting malformed archives and anything over [maxEntries] or [maxEntryBytes]. */
    suspend fun read(zip: ByteArray, inflate: Inflate, maxEntries: Int = 3000, maxEntryBytes: Int = 25 * 1024 * 1024): List<ZipEntry> {
        val end = (zip.size - 22 downTo maxOf(0, zip.size - 22 - 65535)).firstOrNull { zip.int(it) == 0x06054b50 }
            ?: error("Not a ZIP backup.")
        val count = zip.short(end + 10)
        var at = zip.int(end + 16)
        require(count <= maxEntries) { "Backup has too many files." }
        val entries = mutableListOf<ZipEntry>()
        repeat(count) {
            require(at >= 0 && at + 46 <= zip.size && zip.int(at) == 0x02014b50) { "Damaged backup." }
            val flags = zip.short(at + 8)
            val method = zip.short(at + 10)
            val crc = zip.int(at + 16).toLong() and 0xffffffffL
            val compressed = zip.int(at + 20)
            val size = zip.int(at + 24)
            val nameLength = zip.short(at + 28)
            val skip = nameLength + zip.short(at + 30) + zip.short(at + 32)
            val local = zip.int(at + 42)
            require(flags and 1 == 0) { "Encrypted backups are not supported." }
            require(compressed >= 0 && size in 0..maxEntryBytes) { "Backup exceeds size limits." }
            val name = zip.copyOfRange(at + 46, at + 46 + nameLength).decodeToString()
            require(local >= 0 && local + 30 <= zip.size && zip.int(local) == 0x04034b50) { "Damaged backup." }
            val data = local + 30 + zip.short(local + 26) + zip.short(local + 28)
            require(data + compressed <= zip.size) { "Damaged backup." }
            val raw = zip.copyOfRange(data, data + compressed)
            val bytes = when (method) {
                0 -> raw
                8 -> inflate.raw(raw, size)
                else -> error("Unsupported compression in backup.")
            }
            require(bytes.size == size && Crc32.of(bytes) == crc) { "Backup entry $name is damaged." }
            if (!name.endsWith("/")) entries += ZipEntry(name, bytes)
            at += 46 + skip
        }
        return entries
    }

    private fun ByteArray.short(at: Int) = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)
    private fun ByteArray.int(at: Int) = short(at) or (short(at + 2) shl 16)
}

private class Bytes {
    private var data = ByteArray(1024)
    var size = 0; private set
    private fun room(n: Int) { if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n)) }
    fun bytes(value: ByteArray) { room(value.size); value.copyInto(data, size); size += value.size }
    fun short(value: Int) { room(2); data[size++] = value.toByte(); data[size++] = (value shr 8).toByte() }
    fun int(value: Int) { short(value and 0xffff); short(value ushr 16) }
    fun toByteArray() = data.copyOf(size)
}

object Crc32 {
    private val table = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
        c
    }

    fun of(data: ByteArray): Long {
        var c = 0xffffffffL
        for (b in data) c = table[((c xor b.toLong()) and 0xff).toInt()] xor (c ushr 8)
        return c xor 0xffffffffL
    }
}
