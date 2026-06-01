package io.rhizome.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Kotlin half of the dual-language conformance contract: load and run the shared vectors in
 * /conformance/vectors (see /conformance/README.md). This is the Phase-0 "running harness" — it
 * DISCOVERS and STRUCTURALLY VALIDATES every vector and dispatches on category. The real `merge`
 * assertion lands with the merge implementation (Phase 1); until then a merge vector is validated
 * for shape only (no silent pass: the gap is recorded in the spec/conformance status table).
 */
class ConformanceTest {

    private val json = Json { ignoreUnknownKeys = true }

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
    fun vectorsParseAndDispatch() {
        val dir = vectorsDir()
        val files = dir.listFiles { f -> f.name.endsWith(".vector.json") }?.sortedBy { it.name }
            ?: emptyList()
        assertTrue(files.isNotEmpty(), "no vectors found in $dir")

        var merge = 0
        var skipped = 0
        for (f in files) {
            val v = json.parseToJsonElement(f.readText()).jsonObject
            val name = v["name"]?.jsonPrimitive?.content
                ?: fail("${f.name}: missing name")
            when (val category = v["category"]?.jsonPrimitive?.content
                ?: fail("${f.name}: missing category")) {
                "merge" -> {
                    assertTrue(
                        (v["ops"]?.jsonArray?.size ?: 0) > 0,
                        "$name: merge vector has no ops",
                    )
                    assertTrue(
                        v["expected_state"] is JsonObject,
                        "$name: merge vector has no expected_state",
                    )
                    merge++ // merge assertion pending Phase 1 (Merge impl)
                }
                else -> skipped++ // category not yet handled by the Kotlin runner
            }
        }
        println("conformance: ${files.size} vectors ($merge merge structurally valid, $skipped other categories deferred)")
    }
}
