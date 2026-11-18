package com.bleural.croissant.send

import com.bleural.croissant.Prefs
import com.bleural.croissant.backup.Answer
import com.bleural.croissant.backup.Archive
import com.bleural.croissant.backup.BackupClient
import com.bleural.croissant.backup.BackupProblem
import com.bleural.croissant.backup.Made
import com.bleural.croissant.data.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * One tape on its way to the Mac, or there: [sent] of [size] bytes while it goes, then where the
 * server kept it ([kept], "~/R1 Tapes/…wav"), or why it did not ([error]).
 */
data class TapeSend(val file: File, val sent: Long = 0L, val size: Long = 0L, val kept: String? = null, val error: String? = null) {
    val going: Boolean get() = kept == null && error == null
    val progress: Float get() = if (size > 0) sent.toFloat() / size else 0f
}

/**
 * A tape's WAV sent whole to croissant-server's `/tape` (server/croissant_server/tapes.py), over
 * the tailnet or the LAN, the way a backup goes: a begin with its size and sha256, 1 MB chunks
 * each answered, the server keeping it only once the hash matches. One at a time.
 */
class TapeToMac(private val scope: CoroutineScope, private val server: () -> Prefs.Server?) {
    private val _state = MutableStateFlow<TapeSend?>(null)
    val state: StateFlow<TapeSend?> = _state
    private var job: Job? = null

    fun send(rec: Recording) {
        if (_state.value?.going == true) return
        val s = server()
        if (s == null) { _state.value = TapeSend(rec.file, error = "no server set"); return }
        _state.value = TapeSend(rec.file, size = rec.file.length())
        job = scope.launch {
            val made = withContext(Dispatchers.IO) { hashed(rec.file) }
            _state.update { it?.copy(size = made.size) }
            val about = mapOf("tape" to rec.file.nameWithoutExtension, "title" to rec.title)
            _state.value = when (val a = BackupClient(s).upload(made, "/tape", about) { n -> _state.update { it?.copy(sent = n) } }) {
                is Answer.Done -> TapeSend(rec.file, made.size, made.size, kept = "${a.value.folder}/${a.value.name}")
                is Answer.Failed -> TapeSend(rec.file, size = made.size, error = said(a.problem))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = null
    }

    private fun hashed(f: File): Made {
        val d = Archive.sha256()
        val buf = ByteArray(256 * 1024)
        var size = 0L
        FileInputStream(f).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
                size += n
            }
        }
        return Made(f, size, Archive.hex(d.digest()))
    }

    private fun said(p: BackupProblem): String = when (p) {
        BackupProblem.NoServer -> "no server set"
        is BackupProblem.Unreachable -> "can't reach ${p.machine}"
        is BackupProblem.TooOld -> "${p.machine} can't take tapes: update croissant-server"
        is BackupProblem.NeedsCode -> "${p.machine} wants its pairing code"
        is BackupProblem.Dropped -> "the connection dropped: ${p.why}"
        is BackupProblem.Damaged -> "it arrived damaged"
        is BackupProblem.DiskFull -> "${p.machine}'s disk is full"
        is BackupProblem.ServerSaid -> p.message
        is BackupProblem.Local -> p.why
        else -> p.toString()
    }
}
