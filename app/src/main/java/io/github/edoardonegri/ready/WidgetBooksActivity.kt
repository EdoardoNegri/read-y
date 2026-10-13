package io.github.edoardonegri.ready

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Opened from the home-screen widget: every book of the library on one case, to choose those that
 * stand on the widget. A tap puts a book on it or takes it off. Those on it are the bright ones,
 * which stand first, in the order they have on the widget; a long press picks one of them up to
 * give it another place there. The lens looks for a book by title, author or file name, and the
 * star beside what is typed leaves only the favorites. It is a task of its own, so that
 * leaving it leads back to the home screen.
 */
class WidgetBooksActivity : AppCompatActivity() {

    private lateinit var shelf: RecyclerView
    private lateinit var searchRow: View
    private lateinit var query: EditText
    private lateinit var star: ImageButton

    /** The star beside the search field is on: only the favorites are shown and searched. */
    private var starred = false

    /** The books being shown, in the order of the library; [show] puts those on the widget first. */
    private var listed: List<File> = emptyList()

    /** Counts the lists asked for, so that a slow one cannot overwrite a later one. */
    private var searches = 0

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }
    private var columns = 3
    // A long press is left to [mover], which picks up the books that stand on the widget.
    private val adapter = ShelfAdapter({ f, _ -> toggle(f) }, ::toggle, { _, _ -> }, ::spec)

    /** Whether the book being carried has taken another place. */
    private var moved = false

    /** Carries a book of the widget to another place among them, which it then has on the widget too. */
    private val mover = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        private fun chosen(h: RecyclerView.ViewHolder) =
            adapter.files.getOrNull(h.bindingAdapterPosition)?.let { Library.isOnWidget(this@WidgetBooksActivity, it) } == true

        override fun getMovementFlags(rv: RecyclerView, h: RecyclerView.ViewHolder) = makeMovementFlags(
            if (chosen(h)) ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT else 0,
            0,
        )

        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            chosen(target)

        override fun onMove(rv: RecyclerView, h: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = h.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            adapter.move(from, to)
            moved = true
            return true
        }

        override fun onSwiped(h: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, h: RecyclerView.ViewHolder) {
            super.clearView(rv, h)
            if (moved) saveOrder()
            moved = false
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without the library there is nothing to choose from; the bookshelf asks for it.
        if (!Library.hasAccess(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        setContentView(R.layout.activity_widget_books)
        shelf = findViewById(R.id.shelf)
        val bar = findViewById<View>(R.id.bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, 0, i.right, 0)
            bar.padRail(i.top)
            shelf.setPadding(shelf.paddingLeft, 0, shelf.paddingRight, i.bottom)
            insets
        }
        findViewById<TextView>(R.id.barTitle).apply {
            setText(R.string.widget_books)
            background = PlaqueDrawable(resources.displayMetrics.density, ornate = true)
        }
        // The lens stands at the very right here, where the bookshelf has its menu.
        findViewById<View>(R.id.search).visibility = View.INVISIBLE
        searchRow = findViewById(R.id.searchRow)
        query = findViewById(R.id.query)
        star = findViewById(R.id.starred)
        star.setOnClickListener {
            starred = !starred
            star.setImageResource(if (starred) R.drawable.ic_star else R.drawable.ic_star_outline)
            list(query.text.toString())
        }
        findViewById<ImageButton>(R.id.more).apply {
            setImageResource(R.drawable.ic_search)
            contentDescription = getString(R.string.search)
            setOnClickListener { if (searchRow.isVisible) closeSearch() else openSearch() }
        }
        findViewById<View>(R.id.closeSearch).setOnClickListener { closeSearch() }
        query.doAfterTextChanged {
            // The library is gone through once the typing pauses, not for every letter.
            query.removeCallbacks(typed)
            if (it.isNullOrEmpty()) list("") else query.postDelayed(typed, TYPING_MS)
        }
        onBackPressedDispatcher.addCallback(this, back)
        findViewById<View>(R.id.up).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }

        columns = (resources.configuration.screenWidthDp / 118).coerceAtLeast(3)
        shelf.layoutManager = GridLayoutManager(this, columns)
        shelf.addItemDecoration(ShelfDecoration(this, columns))
        spec().let { shelf.setPadding(it.side, shelf.paddingTop, it.side, shelf.paddingBottom) }
        shelf.adapter = adapter
        mover.attachToRecyclerView(shelf)

        Library.adoptBackup(this)
        list("")
    }

    private val typed = Runnable { list(query.text.toString()) }

    private fun openSearch() {
        searchRow.visibility = View.VISIBLE
        back.isEnabled = true
        query.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(query, 0)
    }

    private fun closeSearch() {
        searchRow.visibility = View.GONE
        back.isEnabled = false
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
        starred = false
        star.setImageResource(R.drawable.ic_star_outline)
        // Clearing the field shows every book again.
        if (query.text.isEmpty()) list("") else query.text.clear()
    }

    /**
     * Shows the books that [text] is found in, or every book if there is none; only the
     * favorites among them while the star is on.
     */
    private fun list(text: String) {
        val run = ++searches
        val app = applicationContext
        val favorites = starred
        Library.io.execute {
            val books =
                if (text.isBlank() && !favorites) Library.books().toList() else Library.search(app, Library.root(), text, favorites)
            val sorted = Library.arrange(app, null, books)
            runOnUiThread { if (run == searches && !isDestroyed) show(sorted) }
        }
    }

    override fun onPause() {
        Library.backUp(this)
        super.onPause()
    }

    private fun show(books: List<File>) {
        listed = books
        adapter.picked = Library.widgetBooks(this).toSet()
        adapter.submit(arranged())
    }

    /** [listed] with the books on the widget first, in the order they have there. */
    private fun arranged(): List<File> {
        val shown = listed.toSet()
        val on = Library.widgetBooks(this)
        return on.filter { it in shown } + listed.filter { it !in on }
    }

    /** Puts [f] on the widget, where it joins the others at their end, or takes it back to the rest. */
    private fun toggle(f: File) {
        Library.setOnWidget(this, f, !Library.isOnWidget(this, f))
        adapter.picked = Library.widgetBooks(this).toSet()
        val from = adapter.indexOf(f)
        val to = arranged().indexOf(f)
        if (from < 0 || to < 0) return
        adapter.changed(f)
        if (to != from) adapter.move(from, to)
    }

    /**
     * The books on the widget take the order they were just given here. Those a search leaves
     * out keep the places they had among them.
     */
    private fun saveOrder() {
        val on = adapter.picked.orEmpty()
        val shown = ArrayDeque(adapter.files.filter { it in on })
        val moving = shown.toSet()
        Library.setWidgetOrder(this, Library.widgetBooks(this).map { if (it in moving) shown.removeFirst() else it })
    }

    private fun spec() = ShelfSpec(shelf.width, columns, resources.displayMetrics.density)

    private companion object {
        const val TYPING_MS = 200L
    }
}
