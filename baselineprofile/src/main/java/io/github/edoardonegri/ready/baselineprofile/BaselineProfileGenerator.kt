package io.github.edoardonegri.ready.baselineprofile

import android.graphics.Rect
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses the app as a reader does, for the baseline profile: it starts, the shelf is scrolled down
 * and up again if it holds more than the screen, and the books in its first two rows are opened in
 * turn and a few of their pages turned. The library folder must have been chosen on the phone it
 * runs on, with books there of each kind the app reads, both laid out anew to fit the screen (EPUB,
 * MOBI, text) and printed in pages (PDF, comic), for the reading of each to be in the profile.
 * Views are found by what they are and where, not by their names, which a shrunk build may shorten.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        val shelf = device.wait(Until.findObject(SHELF), WAIT_MS)
            ?: error("The shelf did not show: is the library folder chosen?")
        // Flung from clear of the edges, which the system keeps for its own gestures; a shelf
        // whose books all fit on the screen does not scroll.
        if (shelf.isScrollable) {
            shelf.setGestureMargin(device.displayWidth / 5)
            shelf.fling(Direction.DOWN)
            device.waitForIdle()
            shelf.fling(Direction.UP)
            device.waitForIdle()
        }
        Thread.sleep(SETTLE_MS)

        for (place in 0 until PLACES) read(place)
    }

    /**
     * Opens the book in the [place] of the shelf, counted from its top left, turns a few of its
     * pages forward and back, and goes back to the shelf; nothing if the shelf has no such place.
     */
    private fun MacrobenchmarkScope.read(place: Int) {
        val book = placeBounds(place) ?: return
        device.click(book.centerX(), book.centerY())
        // The book opens on the shelf before the reader shows it.
        Thread.sleep(OPEN_MS)
        repeat(PAGES) {
            device.click(device.displayWidth * 9 / 10, device.displayHeight / 2)
            Thread.sleep(TURN_MS)
        }
        repeat(PAGES) {
            device.click(device.displayWidth / 10, device.displayHeight / 2)
            Thread.sleep(TURN_MS)
        }
        device.pressBack()
        device.wait(Until.hasObject(SHELF), WAIT_MS)
        Thread.sleep(SETTLE_MS)
    }

    /**
     * Where the [place] of the shelf is on the screen, found anew: the shelf found before may have
     * been replaced since, as when it is come back to from a book. Null if it has no such place.
     */
    private fun MacrobenchmarkScope.placeBounds(place: Int): Rect? {
        repeat(TRIES) {
            val shelf = device.wait(Until.findObject(SHELF), WAIT_MS)
                ?: error("The shelf did not show: is the library folder chosen?")
            try {
                return shelf.children.getOrNull(place)?.visibleBounds
            } catch (e: StaleObjectException) {
                Thread.sleep(SETTLE_MS)
            }
        }
        error("The shelf kept changing")
    }

    private companion object {
        const val PACKAGE = "com.edoardonegri.readY"

        /** The shelf, the grid of books: by what it is rather than by its name, see the class. */
        val SHELF: BySelector = By.pkg(PACKAGE).clazz("android.widget.GridView")
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 600L
        const val OPEN_MS = 2_000L
        const val TURN_MS = 700L
        const val PAGES = 4

        /** How many places of the shelf are read: its first two rows. */
        const val PLACES = 6

        /** How many times the shelf is looked for while it keeps changing. */
        const val TRIES = 3
    }
}
