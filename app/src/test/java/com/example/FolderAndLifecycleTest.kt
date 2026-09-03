package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.DjAudioEngine
import com.example.model.Track
import com.example.model.TrackFolder
import com.example.ui.LocalCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderAndLifecycleTest {

    @Test
    fun testLocalCategoryContainsFolders() {
        val folderCategory = LocalCategory.entries.find { it == LocalCategory.FOLDERS }
        assertNotNull(folderCategory)
        assertEquals("Folders", folderCategory?.label)
    }

    @Test
    fun testTrackFolderGrouping() {
        val tracks = listOf(
            Track(
                id = "1",
                title = "Track One",
                artist = "Artist A",
                filePath = "/storage/emulated/0/Music/Techno/01.mp3",
                directoryPath = "/storage/emulated/0/Music/Techno",
                durationSeconds = 180
            ),
            Track(
                id = "2",
                title = "Track Two",
                artist = "Artist A",
                filePath = "/storage/emulated/0/Music/Techno/02.mp3",
                directoryPath = "/storage/emulated/0/Music/Techno",
                durationSeconds = 240
            ),
            Track(
                id = "3",
                title = "Ambient Sound",
                artist = "Artist B",
                filePath = "/storage/emulated/0/Music/Ambient/intro.flac",
                directoryPath = "/storage/emulated/0/Music/Ambient",
                durationSeconds = 300
            )
        )

        val folders: List<TrackFolder> = tracks.groupBy { track ->
            val dir = track.directoryPath.ifBlank {
                java.io.File(track.filePath).parent ?: "/Music"
            }
            dir.trimEnd('/')
        }.map { (folderPath, folderTracks) ->
            val folderName = folderPath.substringAfterLast('/').ifBlank { folderPath.ifBlank { "Root" } }
            TrackFolder(
                id = "folder_${folderPath.hashCode()}",
                name = folderName,
                path = folderPath,
                trackCount = folderTracks.size,
                totalDurationSeconds = folderTracks.sumOf { it.durationSeconds },
                tracks = folderTracks
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }

        assertEquals(2, folders.size)

        val ambientFolder = folders.find { it.name == "Ambient" }
        assertNotNull(ambientFolder)
        assertEquals(1, ambientFolder?.trackCount)
        assertEquals(300, ambientFolder?.totalDurationSeconds)

        val technoFolder = folders.find { it.name == "Techno" }
        assertNotNull(technoFolder)
        assertEquals(2, technoFolder?.trackCount)
        assertEquals(420, technoFolder?.totalDurationSeconds)
    }

    @Test
    fun testDjAudioEngineLifecycleRecreatesAfterRelease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine1 = DjAudioEngine.getInstance(context)
        assertNotNull(engine1)

        // Release engine1
        engine1.release()

        // Calling getInstance after release must provide a fresh, un-released engine
        val engine2 = DjAudioEngine.getInstance(context)
        assertNotNull(engine2)
        assertFalse(engine2 === engine1)

        // Clean up
        engine2.release()
    }

    @Test
    fun testAudioEffectsEngineParameters() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DjAudioEngine.getInstance(context)

        engine.setEqEnabled(true)
        assertTrue(engine.eqEnabled.value)

        engine.setEq(1.5f, 0.8f, 1.2f)
        assertEquals(1.5f, engine.eqLow.value, 0.01f)
        assertEquals(0.8f, engine.eqMid.value, 0.01f)
        assertEquals(1.2f, engine.eqHigh.value, 0.01f)

        engine.setHaasEnabled(true)
        assertTrue(engine.haasEnabled.value)

        engine.setHaasAmount(0.75f)
        assertEquals(0.75f, engine.haasAmount.value, 0.01f)

        engine.setHaasDelayMs(15f)
        assertEquals(15f, engine.haasDelayMs.value, 0.01f)

        engine.release()
    }
}
