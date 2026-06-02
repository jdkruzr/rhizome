package io.rhizome.example

import java.security.SecureRandom

/**
 * A minimal ULID minter for the demo: a 48-bit millisecond timestamp + 80 bits of crypto-random,
 * rendered as 26 uppercase Crockford base32 chars. Matches the server's `IsULID`/`NewULID` bit
 * layout (server-go/syncstore/ulid.go) so client-minted site ids and row pks validate server-side.
 * ASCII order equals time order, which the LWW tie-break (op_ts, op_seq, site_id) relies on.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32, uppercase
    private val rng = SecureRandom()

    fun mint(): String {
        val b = ByteArray(16)
        val ms = System.currentTimeMillis()
        b[0] = (ms ushr 40).toByte()
        b[1] = (ms ushr 32).toByte()
        b[2] = (ms ushr 24).toByte()
        b[3] = (ms ushr 16).toByte()
        b[4] = (ms ushr 8).toByte()
        b[5] = ms.toByte()
        val tail = ByteArray(10)
        rng.nextBytes(tail)
        tail.copyInto(b, destinationOffset = 6)
        return encode(b)
    }

    /** Render 16 raw bytes as 26 Crockford base32 chars, 5 bits at a time, MSB-first. */
    private fun encode(b: ByteArray): String {
        fun u(i: Int) = b[i].toInt() and 0xFF
        val d = CharArray(26)
        d[0] = ALPHABET[(u(0) and 224) ushr 5]
        d[1] = ALPHABET[u(0) and 31]
        d[2] = ALPHABET[(u(1) and 248) ushr 3]
        d[3] = ALPHABET[((u(1) and 7) shl 2) or ((u(2) and 192) ushr 6)]
        d[4] = ALPHABET[(u(2) and 62) ushr 1]
        d[5] = ALPHABET[((u(2) and 1) shl 4) or ((u(3) and 240) ushr 4)]
        d[6] = ALPHABET[((u(3) and 15) shl 1) or ((u(4) and 128) ushr 7)]
        d[7] = ALPHABET[(u(4) and 124) ushr 2]
        d[8] = ALPHABET[((u(4) and 3) shl 3) or ((u(5) and 224) ushr 5)]
        d[9] = ALPHABET[u(5) and 31]
        d[10] = ALPHABET[(u(6) and 248) ushr 3]
        d[11] = ALPHABET[((u(6) and 7) shl 2) or ((u(7) and 192) ushr 6)]
        d[12] = ALPHABET[(u(7) and 62) ushr 1]
        d[13] = ALPHABET[((u(7) and 1) shl 4) or ((u(8) and 240) ushr 4)]
        d[14] = ALPHABET[((u(8) and 15) shl 1) or ((u(9) and 128) ushr 7)]
        d[15] = ALPHABET[(u(9) and 124) ushr 2]
        d[16] = ALPHABET[((u(9) and 3) shl 3) or ((u(10) and 224) ushr 5)]
        d[17] = ALPHABET[u(10) and 31]
        d[18] = ALPHABET[(u(11) and 248) ushr 3]
        d[19] = ALPHABET[((u(11) and 7) shl 2) or ((u(12) and 192) ushr 6)]
        d[20] = ALPHABET[(u(12) and 62) ushr 1]
        d[21] = ALPHABET[((u(12) and 1) shl 4) or ((u(13) and 240) ushr 4)]
        d[22] = ALPHABET[((u(13) and 15) shl 1) or ((u(14) and 128) ushr 7)]
        d[23] = ALPHABET[(u(14) and 124) ushr 2]
        d[24] = ALPHABET[((u(14) and 3) shl 3) or ((u(15) and 224) ushr 5)]
        d[25] = ALPHABET[u(15) and 31]
        return String(d)
    }
}
