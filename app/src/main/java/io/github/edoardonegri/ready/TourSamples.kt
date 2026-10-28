package io.github.edoardonegri.ready

import android.content.Context
import java.io.File

/**
 * The book and the shelf made for the tour of the bookshelf (Tutorial), to do what it asks on without
 * touching the reader's own: each is made as the tour comes to the first step about it, and both are
 * taken away when it ends, however it ends. All of it blocks, and is done off the thread that draws.
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

    /** Makes [file], which is [book] or [shelf], if it is not there yet; false if it could not be made. */
    fun create(ctx: Context, file: File): Boolean {
        setTourSamplesPending(ctx, true)
        if (Store.exists(file)) return true
        if (file == shelf()) return Library.createShelf(Library.root(), SHELF)
        return try {
            Store.writeText(file, TEXT)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Takes them away for good, if they are there. */
    fun remove(ctx: Context) {
        for (f in listOf(book(), shelf())) {
            if (Store.exists(f)) Library.delete(ctx, f)?.let { Library.purge(ctx, it) }
        }
        setTourSamplesPending(ctx, false)
    }
}
