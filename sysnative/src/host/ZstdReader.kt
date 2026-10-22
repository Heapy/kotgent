@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.kotgent.host

import io.kotgent.cinterop.zstd.*
import kotlinx.cinterop.*
import kotlinx.coroutines.yield

/** Logical byte positions always refer to decoded content, including for compressed files. */
sealed interface DecodedFileRead {
    data class Window(val bytes: ByteArray, val logicalOffset: Long) : DecodedFileRead
    data object Missing : DecodedFileRead
    data object Unreadable : DecodedFileRead
    data object ResourceLimit : DecodedFileRead
}

enum class RegularFileStatus { REGULAR, MISSING, UNREADABLE }

fun regularFileStatus(path: String): RegularFileStatus = when (kg_regular_status(path)) {
    0 -> RegularFileStatus.REGULAR
    -1 -> RegularFileStatus.MISSING
    else -> RegularFileStatus.UNREADABLE
}

// Bounds are independent of advertised frame sizes, line lengths, and compression ratio.
const val DECODED_WINDOW_MAX_BYTES: Int = 256 * 1024
const val DECODE_CHUNK_BYTES: Int = 32 * 1024
const val PREFIX_MAX_INPUT_BYTES: Long = 8 * 1024 * 1024
const val PREFIX_MAX_STEPS: Int = 4096

/** A prefix deliberately does not validate the unread remainder of a stream. */
fun readDecodedPrefix(path: String, compressed: Boolean, bytes: Int): DecodedFileRead {
    require(bytes in 1..DECODED_WINDOW_MAX_BYTES)
    return withReader(path, compressed) { reader ->
        val output = ByteArray(bytes)
        var size = 0
        repeat(PREFIX_MAX_STEPS) {
            if (kg_reader_input_bytes(reader).toLong() >= PREFIX_MAX_INPUT_BYTES) return@withReader DecodedFileRead.ResourceLimit
            val step = step(reader, output, size, minOf(DECODE_CHUNK_BYTES, bytes - size))
            if (step.status < 0) return@withReader failure(step.status)
            size += step.produced
            if (size == bytes || step.status == 1) return@withReader DecodedFileRead.Window(output.copyOf(size), 0)
        }
        DecodedFileRead.ResourceLimit
    }
}

/** Plain tails seek directly. Compressed tails become publishable only after validated frame EOF. */
suspend fun readDecodedTail(path: String, compressed: Boolean, bytes: Int): DecodedFileRead {
    require(bytes in 1..DECODED_WINDOW_MAX_BYTES)
    return withReader(path, compressed) { reader ->
        var offset = 0L
        if (!compressed) {
            offset = kg_reader_seek_tail(reader, bytes)
            if (offset < 0) return@withReader DecodedFileRead.Unreadable
        }
        val ring = ByteArray(bytes)
        val chunk = ByteArray(minOf(bytes, DECODE_CHUNK_BYTES))
        var total = 0L
        var next = 0
        while (true) {
            yield() // Cancellation escapes through withReader's finally, including on a single-thread dispatcher.
            val want = if (compressed) chunk.size else minOf(chunk.size.toLong(), bytes - total).toInt()
            if (want == 0) break
            val step = step(reader, chunk, 0, want)
            if (step.status < 0) return@withReader failure(step.status)
            if (total > Long.MAX_VALUE - step.produced) return@withReader DecodedFileRead.ResourceLimit
            total += step.produced
            val first = minOf(step.produced, bytes - next)
            chunk.copyInto(ring, next, 0, first)
            chunk.copyInto(ring, 0, first, step.produced)
            next = (next + step.produced) % bytes
            if (step.status == 1) break
        }
        val retained = minOf(total, bytes.toLong()).toInt()
        val output = ByteArray(retained)
        val start = if (total >= bytes) next else 0
        val first = minOf(retained, bytes - start)
        ring.copyInto(output, 0, start, start + first)
        ring.copyInto(output, first, 0, retained - first)
        DecodedFileRead.Window(output, offset + total - retained)
    }
}

private inline fun withReader(path: String, compressed: Boolean, block: (COpaquePointer) -> DecodedFileRead): DecodedFileRead = memScoped {
    val status = alloc<IntVar>()
    val reader = kg_reader_open(path, if (compressed) 1 else 0, status.ptr) ?: return failure(status.value)
    try { block(reader) } finally { kg_reader_close(reader) }
}

private data class DecodeStep(val status: Int, val produced: Int)

private fun step(reader: COpaquePointer, output: ByteArray, offset: Int, capacity: Int): DecodeStep = memScoped {
    val produced = alloc<IntVar>()
    val status = output.usePinned { kg_reader_step(reader, it.addressOf(offset), capacity, produced.ptr) }
    DecodeStep(status, produced.value)
}

private fun failure(status: Int): DecodedFileRead = when (status) {
    -1 -> DecodedFileRead.Missing
    -3 -> DecodedFileRead.ResourceLimit
    else -> DecodedFileRead.Unreadable
}
