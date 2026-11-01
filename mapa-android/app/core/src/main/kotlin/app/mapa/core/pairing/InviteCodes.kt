package app.mapa.core.pairing

import java.security.SecureRandom

/**
 * Invite codes the parent reads out or sends to a family member. Six characters from an
 * alphabet without look-alikes (no 0/O, 1/I/L), shown as "ABC-234". About a billion
 * combinations and a 24-hour expiry; the server never lets anyone list codes.
 */
object InviteCodes {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LENGTH = 6
    const val TTL_MS = 24L * 60 * 60 * 1000

    private val random = SecureRandom()

    fun generate(): String = buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** "abc 234", "ABC-234" and "abc234" all become "ABC234"; anything else returns null. */
    fun normalize(input: String): String? {
        val s = input.uppercase().filter { it.isLetterOrDigit() }
        return s.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    fun display(code: String): String = code.chunked(3).joinToString("-")
}
