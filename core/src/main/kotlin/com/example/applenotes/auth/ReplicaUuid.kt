package com.example.applenotes.auth

import java.security.SecureRandom

/**
 * The pure part of the replica identity: minting and checking the 16-byte
 * RFC 4122 v4 UUID a topotext replica is identified by. Storing it (once per
 * install, forever) is the app's job: `DeviceIdentity` (DataStore).
 *
 * Apple's clients (iCloud.com, Mac, iOS) all produce properly-formatted v4
 * UUIDs, and Mac's notesync was observed trashing records whose sole replica
 * had a non-v4 UUID. See docs/protocol.md.
 */
object ReplicaUuid {
    /**
     * Generate a properly-formatted v4 (random) UUID per RFC 4122 §4.4.
     * Sets byte 6's high nibble to 0x4 (version=4) and byte 8's high two
     * bits to 0b10 (variant=DCE/RFC 4122). Apple Notes requires v4.
     */
    fun generateV4(): ByteArray {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()  // version = 4
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()  // variant = 10
        return bytes
    }

    fun isValidV4(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val versionNibble = (bytes[6].toInt() ushr 4) and 0x0F
        val variantBits = (bytes[8].toInt() ushr 6) and 0x03
        return versionNibble == 4 && variantBits == 0b10
    }

    fun hexEncode(b: ByteArray): String =
        b.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    fun hexDecode(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
