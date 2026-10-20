package com.obd2dashboard.backend.archive

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * **A car's sessions as one log** (M29): their stored lines back to back, in
 * the order given, every byte as the tablet sent it, VIN and all. Each
 * session begins with its own `session` header line, and that says where one
 * ends and the next begins (`seq` counts from 0 again after it). A session
 * whose last stored line has no newline gets one, so two sessions' lines never
 * run together. Streams; never holds a session.
 */
public object MergedLog {
    /**
     * Writes [sessions], each as [read] streams it (`ArchiveService.read`), to [out]. [line] is told each line,
     * without its newline, with its session, as it goes.
     */
    public suspend fun write(
        sessions: List<SessionRecord>,
        read: suspend (SessionRecord, suspend (InputStream) -> Unit) -> Unit,
        out: OutputStream,
        line: (SessionRecord, ByteArray) -> Unit = { _, _ -> },
    ) {
        val buffer = ByteArray(64 * 1024)
        val partial = ByteArrayOutputStream(1024)
        for (session in sessions) {
            var last: Byte? = null
            partial.reset()
            // A session's pieces each come as a stream (M19.3): one line can only end within one.
            read(session) { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    last = buffer[n - 1]
                    var start = 0
                    for (i in 0 until n) {
                        if (buffer[i] == NEWLINE) {
                            partial.write(buffer, start, i - start)
                            line(session, partial.toByteArray())
                            partial.reset()
                            start = i + 1
                        }
                    }
                    partial.write(buffer, start, n - start)
                }
            }
            if (last != null && last != NEWLINE) {
                out.write(NEWLINE.toInt())
                line(session, partial.toByteArray())
            }
        }
        out.flush()
    }

    private const val NEWLINE = '\n'.code.toByte()
}
