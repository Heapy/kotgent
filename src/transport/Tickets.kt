package io.kotgent.transport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock


const val TICKET_TTL_MILLIS: Long = 5L * 60 * 1000

const val TICKET_CODE_ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

const val TICKET_CODE_BYTES: Int = 5

const val TICKET_CODE_LENGTH: Int = TICKET_CODE_BYTES * 8 / 5

data class Ticket(val value: String, val expiresAt: Long)

fun crockfordBase32(bytes: ByteArray): String {
    require(bytes.size == TICKET_CODE_BYTES) { "a login code is exactly $TICKET_CODE_BYTES bytes, got ${bytes.size}" }
    var bits = 0L
    for (byte in bytes) bits = (bits shl 8) or (byte.toLong() and 0xFF)
    val code = CharArray(TICKET_CODE_LENGTH)
    for (i in 0 until TICKET_CODE_LENGTH) {
        val shift = 5 * (TICKET_CODE_LENGTH - 1 - i)
        code[i] = TICKET_CODE_ALPHABET[((bits shr shift) and 0x1F).toInt()]
    }
    return code.concatToString()
}

fun normalizeTicketCode(raw: String): String {
    val out = StringBuilder(raw.length)
    for (ch in raw) {
        if (ch.isWhitespace() || ch == '-') continue
        out.append(
            when (ch) {
                'I', 'i', 'L', 'l' -> '1'
                'O', 'o' -> '0'
                else -> ch.uppercaseChar()
            },
        )
    }
    return out.toString()
}

class TicketStore(
    private val now: () -> Long = ::ticketEpochMillis,
    private val ttlMillis: Long = TICKET_TTL_MILLIS,
) {
    // Forty bits is a deliberate typing trade: security also depends on global rate limiting,
    // this short TTL, and atomic single-use redemption. Outstanding tickets die on daemon restart.
    init {
        require(ttlMillis > 0) { "ticket TTL must be positive, got $ttlMillis ms" }
    }

    private val mutex = Mutex()

    private data class Outstanding(val expiresAt: Long, val boundToken: String)

    private val outstanding = mutableMapOf<String, Outstanding>()

    suspend fun issue(boundToken: String): Ticket = mutex.withLock {
        // Retain the mint-time token so rotation makes any later exchange's cookie dead on arrival.
        val at = now()
        purgeExpired(at)
        val ticket = Ticket(value = crockfordBase32(randomBytes(TICKET_CODE_BYTES)), expiresAt = at + ttlMillis)
        outstanding[ticket.value] = Outstanding(ticket.expiresAt, boundToken)
        ticket
    }

    suspend fun redeem(value: String): String? = mutex.withLock {
        // Removal under the same mutex gives exactly one winner to concurrent replays.
        purgeExpired(now())
        outstanding.remove(normalizeTicketCode(value))?.boundToken
    }

    suspend fun outstandingCount(): Int = mutex.withLock {
        purgeExpired(now())
        outstanding.size
    }

    private fun purgeExpired(at: Long) {
        if (outstanding.isEmpty()) return
        val iterator = outstanding.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAt <= at) iterator.remove()
        }
    }
}

private fun ticketEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
