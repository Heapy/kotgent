package io.kotgent.tmux

import io.kotgent.core.PaneId

interface TmuxControl {
    fun sessionName(id: String): String

    fun newSession(id: String, cwd: String, cmd: String, cols: Int, rows: Int): PaneId

    fun listPanes(): List<TmuxPane>

    fun killSession(id: String): Boolean

    /**
     * A successful non-empty return means byte-exact delivery was verified. Absence and unanswered
     * verification throw [TmuxException].
     */
    fun sendKeys(id: String, bytes: ByteArray)

    /**
     * True only after an answered clear state or soft absence. An unanswered check is not evidence
     * that subsequent programmatic input will reach the pane process.
     */
    fun leaveCopyMode(id: String): Boolean
}
