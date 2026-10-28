package io.github.edoardonegri.ready

import android.content.Context
import java.io.File

/**
 * The book and the shelf made for the tour of the bookshelf (Tutorial), to do what it asks on without
 * touching the reader's own: they stand in the library while the tour goes on, and are taken away
 * when it ends, however it ends. All of it blocks, and is done off the thread that draws.
 */
object TourSamples {
    private const val BOOK = "Test book.txt"
    private const val SHELF = "Test shelf"

    fun book() = File(Library.root(), BOOK)

    fun shelf() = File(Library.root(), SHELF)

    /** What the book says: some days of a few paragraphs each, enough for several pages to turn. */
    private val TEXT = buildString {
        for (day in 1..6) {
            append("Day ").append(day).append("\n\n")
            repeat(3) { part ->
                append("This is a test book, made for the tour of Read-y, and it goes away when the tour ends. ")
                append("Turn the page to read on. ")
                append("The lamp burned low while the rain tapped on the window. ")
                append("(Day ").append(day).append(", paragraph ").append(part + 1).append(")\n\n")
            }
        }
    }

    /** Makes them, new, whatever was left of an earlier tour; false if either could not be made. */
    fun create(ctx: Context): Boolean {
        remove(ctx)
        setTourSamplesPending(ctx, true)
        val made = Library.createShelf(Library.root(), SHELF)
        val wrote = try {
            Store.writeText(book(), TEXT)
            true
        } catch (e: Exception) {
            false
        }
        return made && wrote
    }

    /** Takes them away for good, if they are there. */
    fun remove(ctx: Context) {
        for (f in listOf(book(), shelf())) {
            if (Store.exists(f)) Library.delete(ctx, f)?.let { Library.purge(ctx, it) }
        }
        setTourSamplesPending(ctx, false)
    }
}
