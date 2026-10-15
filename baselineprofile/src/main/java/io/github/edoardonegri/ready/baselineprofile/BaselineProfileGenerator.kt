package io.github.edoardonegri.ready.baselineprofile

import android.graphics.Rect
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses the app as a reader does, for the baseline profile: it starts, the shelf is scrolled down
 * and up again, and the first two books on it are opened in turn and a few of their pages turned.
 * The library folder must have been chosen on the phone it runs on, with two books first on the
 * shelf, one of them laid out anew to fit the screen (EPUB, MOBI, text) and the other printed in
 * pages (PDF, comic), for both kinds of reader to be in the profile. Views are found by what they
 * are and where, not by their names, which a shrunk build may shorten.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        val shelf = device.wait(Until.findObject(By.pkg(PACKAGE).scrollable(true)), WAIT_MS)
            ?: error("The shelf did not show: is the library folder chosen?")
        // Flung from clear of the edges, which the system keeps for its own gestures.
        shelf.setGestureMargin(device.displayWidth / 5)
        shelf.fling(Direction.DOWN)
        device.waitForIdle()
        shelf.fling(Direction.UP)
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)

        // The first two places on the shelf, in the top left of it. The book read last comes
        // first, so the two take turns from one run to the next.
        read(column = 0)
        read(column = 1)
    }

    /** Opens the book in the [column] of the top row of the shelf, turns a few of its pages forward and back, and goes back to the shelf. */
    private fun MacrobenchmarkScope.read(column: Int) {
        val area = shelfBounds()
        device.click(area.left + area.width() * (2 * column + 1) / 6, area.top + area.height() / 7)
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
        device.wait(Until.hasObject(By.pkg(PACKAGE).scrollable(true)), WAIT_MS)
        Thread.sleep(SETTLE_MS)
    }

    /**
     * Where the shelf is on the screen, found anew: the one found before may have been replaced
     * since, as when the shelf is come back to from a book.
     */
    private fun MacrobenchmarkScope.shelfBounds(): Rect {
        repeat(TRIES) {
            val shelf = device.wait(Until.findObject(By.pkg(PACKAGE).scrollable(true)), WAIT_MS)
                ?: error("The shelf did not show: is the library folder chosen?")
            try {
                return shelf.visibleBounds
            } catch (e: StaleObjectException) {
                Thread.sleep(SETTLE_MS)
            }
        }
        error("The shelf kept changing")
    }

    private companion object {
        const val PACKAGE = "com.edoardonegri.readY"
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 600L
        const val OPEN_MS = 2_000L
        const val TURN_MS = 700L
        const val PAGES = 4

        /** How many times the shelf is looked for while it keeps changing. */
        const val TRIES = 3
    }
}
