package audio.own.music.library

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The exported schema is the one the code builds. A change to the tables without a new version
 * (and its exported file) fails here, before it reaches a phone whose catalogue Room would then
 * refuse to open. Migrations between versions get their tests here too, once there is a second
 * version (with room-testing's MigrationTestHelper).
 */
@RunWith(RobolectricTestRunner::class)
class CatalogueSchemaTest {
    @Test fun `the exported schema matches the database`() {
        val db = memoryDatabase()
        try {
            val version = db.openHelper.readableDatabase.version
            val file = File("schemas/audio.own.music.library.db.CatalogueDatabase/$version.json")
            assertTrue("no exported schema for version $version at ${file.absolutePath}", file.exists())
            val exported = (Json.parseToJsonElement(file.readText()) as JsonObject)["database"]!!.jsonObject
            assertEquals(version, exported["version"]!!.jsonPrimitive.int)
            val hash = db.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table").use {
                it.moveToFirst()
                it.getString(0)
            }
            assertEquals(exported["identityHash"]!!.jsonPrimitive.content, hash)
        } finally {
            db.close()
        }
    }
}
