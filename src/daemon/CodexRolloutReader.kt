package io.kotgent.daemon

import io.kotgent.host.DecodedFileRead
import io.kotgent.host.readDecodedPrefix
import io.kotgent.host.readDecodedTail

/** All consumers share sibling precedence and one retry for a resolve/open disappearance. */
internal class CodexRolloutReader(
    private val resolve: (RolloutLocation) -> RolloutRepresentation? = { resolveRollout(it) },
    private val prefixRead: (String, Boolean, Int) -> DecodedFileRead = ::readDecodedPrefix,
    private val tailRead: suspend (String, Boolean, Int) -> DecodedFileRead = ::readDecodedTail,
) {
    fun prefix(location: RolloutLocation, bytes: Int): DecodedFileRead {
        repeat(2) {
            val selected = resolve(location) ?: return DecodedFileRead.Missing
            val read = prefixRead(selected.path, selected.compressed, bytes)
            if (read != DecodedFileRead.Missing) return read
        }
        return DecodedFileRead.Missing
    }

    suspend fun tail(location: RolloutLocation, bytes: Int): DecodedFileRead {
        repeat(2) {
            val selected = resolve(location) ?: return DecodedFileRead.Missing
            val read = tailRead(selected.path, selected.compressed, bytes)
            if (read != DecodedFileRead.Missing) return read
        }
        return DecodedFileRead.Missing
    }
}
