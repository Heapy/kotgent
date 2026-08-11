package io.kotgent.pty

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

class FakePtyHandle(val command: List<String>) : PtyHandle {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)

    override val output: ReceiveChannel<ByteArray> get() = channel

    val written: MutableList<ByteArray> = mutableListOf()

    val resizes: MutableList<Pair<Int, Int>> = mutableListOf()

    var closed: Boolean = false
        private set

    var closePrepared: Boolean = false
        private set

    var failWrites: Boolean = false

    // Models a later POSIX write failing after an earlier call delivered this prefix.
    var failWritesAfterBytes: Int? = null

    // Deterministic cross-thread gates for the write-versus-close regression.
    var beforeWrite: (() -> Unit)? = null

    var afterPrepareClose: (() -> Unit)? = null

    fun emit(bytes: ByteArray) {
        channel.trySend(bytes)
    }

    fun eof() {
        channel.close()
    }

    override fun write(bytes: ByteArray) {
        beforeWrite?.invoke()
        if (failWrites) throw IllegalStateException("fake pty write failed (closed master fd)")
        failWritesAfterBytes?.let { count ->
            val prefixSize = count.coerceIn(0, bytes.size)
            if (prefixSize > 0) written.add(bytes.copyOfRange(0, prefixSize))
            throw IllegalStateException("fake pty write failed after $prefixSize bytes")
        }
        written.add(bytes)
    }

    override fun resize(cols: Int, rows: Int) {
        resizes.add(cols to rows)
    }

    override fun prepareClose() {
        closePrepared = true
        afterPrepareClose?.invoke()
    }

    override fun close() {
        if (closed) return
        prepareClose()
        closed = true
        channel.close()
    }
}

class FakePtyFactory : PtyFactory {
    val created: MutableList<FakePtyHandle> = mutableListOf()

    var lastEnv: Map<String, String> = emptyMap()
        private set

    override fun invoke(command: List<String>, env: Map<String, String>): PtyHandle =
        FakePtyHandle(command).also { created.add(it); lastEnv = env }

    val openCount: Int get() = created.size

    val current: FakePtyHandle get() = created.last()
}
