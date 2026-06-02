package io.rhizome.example

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The toy registry must reproduce the schema hash documented in examples/toy-schema/README.md —
 * the SAME value the Go server's TestToyRegistryReproducesDocumentedHash asserts. Equal hashes on
 * both sides are the cross-language agreement the sync contract rides on.
 */
class ToyRegistryTest {

    private val expectedHash = "099b9cbab8ce15f934ccf27e7638af84a84cd13d2c9cdf8df840cf98307b4ff9"

    @Test
    fun reproducesDocumentedSchemaHash() {
        assertEquals(expectedHash, toyRegistry().schemaHash())
    }

    @Test
    fun canonicalStringMatchesDoc() {
        assertEquals(
            "note:body,created_at,deleted_at,title;tag:created_at,deleted_at,label,note_id",
            toyRegistry().canonical(),
        )
    }

    @Test
    fun mintedUlidsAreValidShape() {
        val id = Ulid.mint()
        assertEquals(26, id.length)
        assertEquals(true, id.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" }, "minted id not Crockford base32: $id")
    }
}
