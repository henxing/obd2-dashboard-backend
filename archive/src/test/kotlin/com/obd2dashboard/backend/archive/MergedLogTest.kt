package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** A car's sessions in a part as one log (M29): every line as stored, back to back. */
class MergedLogTest {
    private fun record(id: String) = SessionRecord(id, "yaris", null, null, 0, emptyList(), true, null, 0, Instant.EPOCH, Instant.EPOCH)

    /** Each session's stored bytes, read in [chunks] pieces as a segmented session is. */
    private fun merge(stored: Map<String, List<String>>, order: List<String> = stored.keys.toList()): Pair<String, List<Pair<String, String>>> {
        val out = ByteArrayOutputStream()
        val lines = mutableListOf<Pair<String, String>>()
        runBlocking {
            MergedLog.write(
                order.map(::record),
                read = { r, reader: suspend (InputStream) -> Unit -> stored.getValue(r.id).forEach { reader(it.toByteArray().inputStream()) } },
                out = out,
                line = { r, bytes -> lines += r.id to bytes.decodeToString() },
            )
        }
        return out.toString(Charsets.UTF_8) to lines
    }

    @Test
    fun `two sessions back to back, byte for byte, each line told with its session`() {
        val a = """{"type":"session","id":"a","vin":"V"}""" + "\n" + """{"type":"sample","seq":1}""" + "\n"
        val b = """{"type":"session","id":"b"}""" + "\n" + """{"type":"sample","seq":1,"note":"é"}""" + "\n"
        val (text, lines) = merge(mapOf("a" to listOf(a), "b" to listOf(b)))
        text shouldBe a + b
        lines.map { it.first } shouldBe listOf("a", "a", "b", "b")
        lines[3].second shouldBe """{"type":"sample","seq":1,"note":"é"}"""
    }

    @Test
    fun `a last line without its newline gets one, so the next session starts on its own line`() {
        val (text, lines) = merge(mapOf("a" to listOf("{\"seq\":0}\n{\"seq\":1}"), "b" to listOf("{\"seq\":0}\n")))
        text shouldBe "{\"seq\":0}\n{\"seq\":1}\n{\"seq\":0}\n"
        lines.map { it.second } shouldBe listOf("{\"seq\":0}", "{\"seq\":1}", "{\"seq\":0}")
    }

    @Test
    fun `a session in pieces, a line across two of them, and an empty session`() {
        val (text, lines) = merge(mapOf("a" to listOf("{\"seq\":0}\n{\"se", "q\":1}\n"), "e" to emptyList(), "b" to listOf("", "{\"seq\":0}\n")))
        text shouldBe "{\"seq\":0}\n{\"seq\":1}\n{\"seq\":0}\n"
        lines shouldBe listOf("a" to "{\"seq\":0}", "a" to "{\"seq\":1}", "b" to "{\"seq\":0}")
    }

    @Test
    fun `in the order given`() {
        val (text, _) = merge(mapOf("a" to listOf("A\n"), "b" to listOf("B\n")), order = listOf("b", "a"))
        text shouldBe "B\nA\n"
    }
}
