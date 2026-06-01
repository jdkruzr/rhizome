package io.rhizome.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The live-cutover guard. ForestNote's registry MUST reproduce its production v3 schema hash, so
 * that when ForestNote + UltraBridge adopt RhizomeSync the existing data and live devices keep
 * syncing with no hash bump. If a refactor changes how the canonical string is built, this goes
 * red before any device is affected. (spec/conformance.md)
 */
class SchemaHashTest {

    private val v3 = "724411eb845ad3487393a77cb5559690e69332c35fdb5ee3e85c1767bf71f3fe"

    @Test
    fun forestNoteRegistryReproducesV3Hash() {
        assertEquals(v3, ForestNoteRegistry.registry.schemaHash())
    }

    @Test
    fun canonicalStringIsTablesThenColumnsAlphabetical() {
        val expected = "folder:created_at,deleted_at,name,parent_folder_id,sort_order;" +
            "notebook:created_at,deleted_at,folder_id,name,sort_order;" +
            "page:created_at,deleted_at,notebook_id,sort_order,template,template_pitch_mm;" +
            "page_text_from_client:created_at,deleted_at,model,ocr_at,text;" +
            "page_text_from_server:created_at,deleted_at,model,ocr_at,text;" +
            "stroke:color,created_at,deleted_at,page_id,pen_width_max,pen_width_min,points,z;" +
            "text_box:border_width,color,created_at,deleted_at,font_name,font_size,height," +
            "page_id,text,weight,width,x,y,z"
        assertEquals(expected, ForestNoteRegistry.registry.canonical())
    }
}
