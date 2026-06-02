package io.rhizome.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Kotlin half of the dual-language conformance contract: load and run the shared vectors in
 * /conformance/vectors (see /conformance/README.md). `merge` vectors are asserted against the
 * deterministic LWW merge using ForestNote's registry for knownCols; other categories are
 * dispatched and counted (their assertions land with their implementations — no silent pass).
 */
class ConformanceTest {

    @Serializable
    private data class ExpectedRow(
        val pk: String,
        @SerialName("site_id") val siteId: String,
        @SerialName("op_seq") val opSeq: Long,
        @SerialName("op_ts") val opTs: Long,
        val cols: JsonObject = JsonObject(emptyMap()),
    )

    @Serializable
    private data class WireCase(val type: String, val wire: JsonElement = JsonNull)

    @Serializable
    private data class Vector(
        val category: String,
        val name: String,
        val description: String = "",
        val ops: List<WireOp> = emptyList(),
        @SerialName("expected_state") val expectedState: Map<String, List<ExpectedRow>> = emptyMap(),
        val cases: List<WireCase> = emptyList(),
        val initial: Long = 0,
        val steps: List<HlcStep> = emptyList(),
    )

    @Serializable
    private data class HlcStep(val op: String, val wall: Long, val remote: Long = 0, val expect: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val knownCols = ForestNoteRegistry.registry.knownCols

    private fun vectorsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val cand = File(dir, "conformance/vectors")
            if (cand.isDirectory) return cand
            dir = dir.parentFile
        }
        fail("could not locate conformance/vectors above ${System.getProperty("user.dir")}")
    }

    @Test
    fun runVectors() {
        val files = vectorsDir().listFiles { f -> f.name.endsWith(".vector.json") }
            ?.sortedBy { it.name } ?: emptyList()
        assertTrue(files.isNotEmpty(), "no vectors found")

        var merge = 0
        var wireCodec = 0
        var hlc = 0
        var deferred = 0
        for (f in files) {
            val v = json.decodeFromString(Vector.serializer(), f.readText())
            when (v.category) {
                "merge" -> { assertMerge(v); merge++ }
                "wire-codec" -> { assertWireCodec(v); wireCodec++ }
                "hlc" -> { assertHlc(v); hlc++ }
                else -> deferred++ // category not yet handled by the Kotlin runner
            }
        }
        assertTrue(merge > 0, "expected at least one merge vector")
        assertTrue(hlc > 0, "expected at least one hlc vector")
        println("conformance: ${files.size} vectors ($merge merge, $wireCodec wire-codec, $hlc hlc asserted, $deferred deferred)")
    }

    private fun assertHlc(v: Vector) {
        var wall = 0L
        val clock = Hlc(last = v.initial, wallClock = { wall })
        for ((i, step) in v.steps.withIndex()) {
            wall = step.wall
            val got = when (step.op) {
                "local" -> clock.localEvent()
                "receive" -> clock.receiveEvent(step.remote)
                else -> fail("${v.name} / step $i: unknown hlc op ${step.op}")
            }
            assertEquals(step.expect, got, "${v.name} / step $i (${step.op}, wall=${step.wall})")
        }
    }

    private fun assertWireCodec(v: Vector) {
        for ((i, case) in v.cases.withIndex()) {
            val type = ColumnType.valueOf(case.type)
            val reEncoded = WireCodec.encode(type, WireCodec.decode(type, case.wire))
            assertEquals(case.wire, reEncoded, "${v.name} / case $i (${case.type}): wire round-trip")
        }
    }

    private fun assertMerge(v: Vector) {
        val winners = Merge.merge(v.ops.map { it.toOp() }, knownCols)
        for ((table, rows) in v.expectedState) {
            val expected = rows.associateBy { it.pk }
            val got = winners.entries.filter { it.key.table == table }.associate { it.key.pk to it.value }
            assertEquals(
                expected.keys, got.keys,
                "${v.name} / $table: surviving pk set mismatch",
            )
            for ((pk, er) in expected) {
                val w = got.getValue(pk)
                assertEquals(er.siteId, w.siteId, "${v.name} / $table / $pk: site_id")
                assertEquals(er.opSeq, w.opSeq, "${v.name} / $table / $pk: op_seq")
                assertEquals(er.opTs, w.opTs, "${v.name} / $table / $pk: op_ts")
                assertEquals(er.cols, w.cols, "${v.name} / $table / $pk: cols")
            }
        }
    }
}
