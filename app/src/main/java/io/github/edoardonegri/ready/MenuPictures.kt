package io.github.edoardonegri.ready

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.TextView
import androidx.core.graphics.createBitmap
import java.io.File

/**
 * Pictures of the menu of the reader, for the book opening on its cover on the shelf, or closing
 * from it, which raise the menu as the cover grows and lower it as it shrinks.
 */
class MenuPictures(
    private val act: Activity,
    private val root: View,
    private val menu: View,
    /** The plaque with the name of the book, on the plank of the [menu]. */
    private val title: TextView,
    private val file: File,
) {
    /**
     * The menu as it stands over the cover that ends the book, from [picture] of its plank, for the
     * book opening on its cover on the shelf or closing from it: with where the plaque with the name
     * of the book is on the plank, which the plaque of the book on the shelf becomes.
     */
    fun endMenu(picture: Bitmap): BookOpening.EndMenu {
        // Where it is in the window, as its own place would tell it less the way its single line is scrolled.
        val at = IntArray(2)
        title.getLocationInWindow(at)
        val plank = IntArray(2)
        menu.findViewById<View>(R.id.menuPlank).getLocationInWindow(plank)
        val left = (at[0] - plank[0]).toFloat()
        val top = (at[1] - plank[1]).toFloat()
        // The plaque pictured as it is drawn, name and all.
        val plaque = try {
            createBitmap(title.width.coerceAtLeast(1), title.height.coerceAtLeast(1)).also { title.draw(Canvas(it)) }
        } catch (e: OutOfMemoryError) {
            null
        }
        val plate = RectF(left, top, left + title.width, top + title.height)
        val known = BookOpening.EndMenu(root.width, root.height, picture.height, plate, title.textSize, title.paddingStart.toFloat())
        // Where it stands is kept, for the book to open on its cover at once the next time (BookOpening.Detached).
        Library.setEndMenu(act, file, known)
        return BookOpening.EndMenu(
            known.screenW, known.screenH, known.height, known.title, known.titleText, known.titleInset, picture.uploaded(), plaque?.uploaded(),
        )
    }

    /**
     * The plank of the menu, pictured as it is, without the plaque with the name of the book: on the
     * shelf the plaque of the book stands for it, which becomes it. Null if it cannot be pictured.
     */
    fun plank(): Bitmap? {
        val room = room(menu)
        if (menu.width == 0 || room <= 0) return null
        return try {
            createBitmap(menu.width, room).also { bmp ->
                val canvas = Canvas(bmp)
                canvas.translate(0f, -(menu.height - room).toFloat())
                title.alpha = 0f
                menu.draw(canvas)
                title.alpha = 1f
            }
        } catch (x: OutOfMemoryError) {
            title.alpha = 1f
            null
        }
    }

    companion object {
        /**
         * How much room the plank of the menu [m] takes at the foot of the screen: the menu is as large as
         * the screen, with an empty place above its plank, and the room of the camera over that.
         */
        fun room(m: View) = m.height - m.findViewById<View>(R.id.menuPlank).top
    }
}
