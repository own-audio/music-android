package audio.own.music.library

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import audio.own.music.library.db.CatalogueDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The exported schema is the one the code builds. A change to the tables without a new version
 * (and its exported file) fails here, before it reaches a phone whose catalogue Room would then
 * refuse to open. Each migration is tested on a database built from the older exported schema.
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

    /** A phone with a version 1 catalogue keeps it: the gain and format columns are added, empty. */
    @Test fun `version 1 migrates to 2 keeping its rows`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("migrate-test.db").also { it.parentFile?.mkdirs(); it.delete() }
        val v1 = (Json.parseToJsonElement(File("schemas/audio.own.music.library.db.CatalogueDatabase/1.json").readText()) as JsonObject)["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            for (entity in v1["entities"]!!.jsonArray) {
                val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                sqlite.execSQL(entity.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach {
                    sqlite.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            v1["setupQueries"]!!.jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
            sqlite.execSQL(
                """INSERT INTO track (serverId, id, title, albumId, albumTitle, artistLine, artistId, discNumber, trackNumber, durationMs,
                genreName, contentType, sizeBytes, image, ownerId, visibility, chapterCount, createdAtMs, updatedAtMs, gen)
                VALUES ('srv', 't1', 'One', 'al', 'First', 'Band', 'band', 1, 1, 200000, NULL, 'audio/flac', 1, NULL, 'u', 'family', 0, 0, 0, 1)""",
            )
            sqlite.version = 1
        }
        val db = Room.databaseBuilder(context, CatalogueDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            val track = db.catalogue().track("srv", "t1")!!.track
            assertEquals("One", track.title)
            assertNull(track.suffix)
            assertNull(track.trackGain)
        } finally {
            db.close()
            file.delete()
        }
    }
}
