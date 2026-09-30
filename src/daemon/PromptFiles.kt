package io.kotgent.daemon

import io.kotgent.core.SessionId
import io.kotgent.transport.writePrivateFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.errno
import platform.posix.mkdir
import platform.posix.strerror

interface PromptFiles {
    fun pathFor(sessionId: SessionId): String

    fun write(sessionId: SessionId, prompt: String)
}

class PrivatePromptFiles(private val dir: String) : PromptFiles {
    override fun pathFor(sessionId: SessionId): String = "$dir/${sessionId.value}.md"

    @OptIn(ExperimentalForeignApi::class)
    override fun write(sessionId: SessionId, prompt: String) {
        if (mkdir(dir, (S_IRUSR or S_IWUSR or S_IXUSR).convert()) != 0) {
            val e = errno
            if (e != EEXIST) error("cannot create prompt directory $dir: ${strerror(e)?.toKString()}")
        }
        writePrivateFile(pathFor(sessionId), prompt.encodeToByteArray())
    }
}

class PromptFilesUnavailableException :
    IllegalStateException("this daemon has no prompt directory, so a session cannot start with a prompt")
