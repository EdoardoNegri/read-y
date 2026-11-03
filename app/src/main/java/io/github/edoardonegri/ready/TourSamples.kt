package io.github.edoardonegri.ready

import android.content.Context
import java.io.File

/**
 * The book and the shelf made for the tour of the bookshelf (Tutorial), to do what it asks on without
 * touching the reader's own: each is made as the tour comes to the first step about it, and both are
 * taken away when it ends, however it ends. All of it blocks, and is done off the thread that draws,
 * one at a time, as the library's two threads may otherwise make and take away at once.
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

    /**
     * Makes [file], which is [book] or [shelf], if it is not there yet; false if it could not be made.
     * One of the reader's own of that name is used as it is, and is not taken away after.
     */
    @Synchronized
    fun create(ctx: Context, file: File): Boolean {
        if (Store.exists(file)) return true
        // Noted before it is made, so that a tour cut short while it is being made takes it away too.
        setTourSamplesMade(ctx, tourSamplesMade(ctx).orEmpty() + file.name)
        if (file == shelf()) return Library.createShelf(Library.root(), SHELF)
        return try {
            Store.writeText(file, TEXT)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Takes away for good those the tour made, if they are there. */
    @Synchronized
    fun remove(ctx: Context) {
        // A tour of before which ones were made was noted made both.
        val made = tourSamplesMade(ctx) ?: setOf(BOOK, SHELF)
        for (f in listOf(book(), shelf())) {
            if (f.name in made && Store.exists(f)) Library.delete(ctx, f)?.let { Library.purge(ctx, it) }
        }
        setTourSamplesMade(ctx, emptySet())
    }
}
