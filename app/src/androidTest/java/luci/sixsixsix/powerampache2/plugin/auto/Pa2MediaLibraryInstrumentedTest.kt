package luci.sixsixsix.powerampache2.plugin.auto

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Automated regression for the Android Auto browse surface: [Pa2MediaLibraryService] exposes a
 * Media3 [MediaLibrarySession], which Android Auto and this test exercise via [MediaBrowser].
 *
 * Run: `./gradlew :app:connectedDebugAndroidTest` (device or emulator required).
 */
@RunWith(AndroidJUnit4::class)
class Pa2MediaLibraryInstrumentedTest {

    @Test
    fun browse_libraryRoot_matchesExpectedIdAndTitle() {
        withBrowser { browser ->
            val rootResult = browser.getLibraryRoot(null).get(TIMEOUT_SEC, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, rootResult.resultCode)
            val root: MediaItem = rootResult.value!!
            assertEquals(ROOT, root.mediaId)
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val expectedTitle = ctx.getString(luci.sixsixsix.powerampache2.plugin.R.string.media_browse_root_title)
            assertEquals(expectedTitle, root.mediaMetadata.title.toString())
        }
    }

    @Test
    fun browse_rootChildren_containsFiveSectionNodes() {
        withBrowser { browser ->
            val childrenResult =
                browser.getChildren(ROOT, /* page= */ 0, /* pageSize= */ 100, /* params= */ null)
                    .get(TIMEOUT_SEC, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, childrenResult.resultCode)
            val children: ImmutableList<MediaItem> = childrenResult.value!!
            assertEquals(5, children.size)
            val ids = children.map { it.mediaId }.toSet()
            assertTrue(ids.contains(SECTION_PLAYLISTS))
            assertTrue(ids.contains(SECTION_FAVOURITE_ALBUMS))
            assertTrue(ids.contains(SECTION_RECENT_ALBUMS))
            assertTrue(ids.contains(SECTION_LATEST_ALBUMS))
            assertTrue(ids.contains(SECTION_HIGHEST_RATED_ALBUMS))
        }
    }

    @Test
    fun browse_rootChildren_respectsPageSize_media3ValidatesResult() {
        withBrowser { browser ->
            val first = browser
                .getChildren(ROOT, /* page= */ 0, /* pageSize= */ 4, /* params= */ null)
                .get(TIMEOUT_SEC, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, first.resultCode)
            assertEquals(4, first.value!!.size)
            val second = browser
                .getChildren(ROOT, /* page= */ 1, /* pageSize= */ 4, /* params= */ null)
                .get(TIMEOUT_SEC, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, second.resultCode)
            assertEquals(1, second.value!!.size)
        }
    }

    @Test
    fun browse_invalidParent_returnsError() {
        withBrowser { browser ->
            val badResult =
                browser.getChildren("invalid/parent/id", 0, 10, null)
                    .get(TIMEOUT_SEC, TimeUnit.SECONDS)
            assertNotNull(badResult)
            assertTrue(
                "expected non-success for unknown parentId",
                badResult.resultCode != LibraryResult.RESULT_SUCCESS
            )
        }
    }

    private fun withBrowser(block: (MediaBrowser) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessionToken = SessionToken(
            context,
            ComponentName(context, Pa2MediaLibraryService::class.java)
        )
        // Connecting can run off the main thread; browse/playback APIs must run on the main thread.
        val browserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()
        val browser = browserFuture.get(TIMEOUT_SEC, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                block(browser)
            }
        } finally {
            instrumentation.runOnMainSync {
                browser.release()
            }
        }
    }

    private companion object {
        private const val TIMEOUT_SEC = 30L

        // Mirrors [MediaIds] (androidTest cannot rely on `internal` from main in all AGP setups).
        private const val ROOT = "root"
        private const val SECTION_PLAYLISTS = "section/playlists"
        private const val SECTION_FAVOURITE_ALBUMS = "section/favourite_albums"
        private const val SECTION_RECENT_ALBUMS = "section/recent_albums"
        private const val SECTION_LATEST_ALBUMS = "section/latest_albums"
        private const val SECTION_HIGHEST_RATED_ALBUMS = "section/highest_rated_albums"
    }
}
