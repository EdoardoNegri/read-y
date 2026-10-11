package com.example.read_y

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.TreeSet
import kotlin.math.roundToInt

/** The bookshelf: mirrors the "Read-y" folder, one shelf screen per folder. Back leaves a sub-folder. */
class MainActivity : AppCompatActivity() {

    private lateinit var current: File
    private lateinit var shelf: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var grant: Button
    private lateinit var ghost: ImageView
    private var columns = 3
    private val adapter = ShelfAdapter(
        ::open, ::showInfo,
    ) { ShelfSpec(shelf.width, columns, resources.displayMetrics.density) }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goUp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        shelf = findViewById(R.id.shelf)
        ghost = findViewById(R.id.ghost)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        grant = findViewById(R.id.grant)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            // The bookcase runs edge to edge behind the system bars; only its rows start below the
            // status bar and can scroll clear of the navigation bar.
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, 0, i.right, 0)
            shelf.setPadding(shelf.paddingLeft, i.top, shelf.paddingRight, i.bottom)
            insets
        }

        columns = (resources.configuration.screenWidthDp / 118).coerceAtLeast(3)
        shelf.layoutManager = GridLayoutManager(this, columns)
        shelf.addItemDecoration(ShelfDecoration(this, columns))
        // Leave room for the bookcase's side posts.
        ShelfSpec(0, columns, resources.displayMetrics.density).let {
            shelf.setPadding(it.post, shelf.paddingTop, it.post, shelf.paddingBottom)
        }
        shelf.adapter = adapter

        current = savedInstanceState?.getString(KEY_DIR)?.let(::File) ?: Library.root()
        grant.setOnClickListener { requestAccess() }
        onBackPressedDispatcher.addCallback(this, back)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DIR, current.path)
    }

    private fun refresh() {
        val root = Library.root()
        if (!Library.hasAccess(this)) {
            back.isEnabled = false
            adapter.submit(emptyList())
            empty.visibility = View.VISIBLE
            grant.visibility = View.VISIBLE
            emptyText.text = getString(R.string.need_access, root.path)
            return
        }
        root.mkdirs()
        if (!current.isDirectory || !current.path.startsWith(root.path)) current = root
        val atRoot = current == root
        val items = Library.list(current)
        back.isEnabled = !atRoot
        adapter.submit(items)
        grant.visibility = View.GONE
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = getString(R.string.empty_folder, current.path)
    }

    private fun goUp() {
        val left = current
        val snapshot = snapshotShelf()
        current = current.parentFile ?: Library.root()
        refresh()
        val index = adapter.indexOf(left)
        if (snapshot == null || index < 0) return
        // Keep showing the shelf being left until the parent is laid out and its miniature can be found.
        shelf.animate().cancel()
        ghost.animate().cancel()
        ghost.scaleX = 1f
        ghost.scaleY = 1f
        ghost.alpha = 1f
        ghost.translationZ = 1f
        ghost.setImageBitmap(snapshot)
        ghost.visibility = View.VISIBLE
        shelf.scrollToPosition(index)
        shelf.doOnPreDraw {
            val thumbnail = shelf.findViewHolderForAdapterPosition(index)?.itemView?.findViewById<View>(R.id.cover)
            if (thumbnail != null) zoom(locate(thumbnail), snapshot, opening = false) else hideGhost()
        }
    }

    private fun open(f: File, from: View? = null) {
        if (f.isDirectory) {
            val miniature = from?.findViewById<View>(R.id.cover)?.let(::locate)
            val snapshot = if (miniature != null) snapshotShelf() else null
            current = f
            refresh()
            shelf.scrollToPosition(0)
            // Start only once the folder's books are laid out, so no frame shows the old shelf shrunk.
            if (miniature != null && snapshot != null) {
                shelf.doOnPreDraw { zoom(miniature, snapshot, opening = true) }
            }
        } else {
            startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, f.path))
        }
    }

    private fun snapshotShelf(): Bitmap? {
        if (shelf.width == 0 || shelf.height == 0) return null
        return try {
            Bitmap.createBitmap(shelf.width, shelf.height, Bitmap.Config.RGB_565).also { shelf.draw(Canvas(it)) }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Where a folder's miniature sits on the shelf: the scale and pivot that map the full shelf onto it. */
    private class Miniature(val px: Float, val py: Float, val scale: Float)

    private fun locate(thumbnail: View): Miniature {
        val at = IntArray(2)
        val origin = IntArray(2)
        thumbnail.getLocationInWindow(at)
        shelf.getLocationInWindow(origin)
        val scale = (thumbnail.width.toFloat() / shelf.width).coerceIn(0.05f, 0.95f)
        // The miniature shows the rows, which on the real shelf start below the top padding.
        val top = at[1] - origin[1] - shelf.paddingTop * scale
        return Miniature((at[0] - origin[0]) / (1 - scale), top / (1 - scale), scale)
    }

    /**
     * Folder transition: a pure change of size. A miniature is the top of its folder's shelf drawn
     * small, so the folder's shelf (always the upper, opaque layer) starts exactly on it, cropped to
     * the same portion, and is enlarged until it fills the screen while the crop opens up. The
     * parent shelf zooms in step underneath. Closing plays the same thing backwards.
     * [snapshot] is the shelf being left; the live list shows the one being entered.
     */
    private fun zoom(m: Miniature, snapshot: Bitmap, opening: Boolean) {
        shelf.animate().cancel()
        ghost.animate().cancel()
        ghost.setImageBitmap(snapshot)
        ghost.visibility = View.VISIBLE
        for (v in arrayOf(shelf, ghost)) {
            v.pivotX = m.px
            v.pivotY = m.py
            v.alpha = 1f
            v.clipBounds = null
        }
        // Opening: the list is the folder (on top), the snapshot is the parent (below). Closing: the reverse.
        val folder: View = if (opening) shelf else ghost
        val w = shelf.width
        val h = shelf.height
        val inset = shelf.paddingTop
        val cropped = (inset + w * 1.5f).toInt().coerceAtMost(h)
        val crop = if (opening) Rect(0, inset, w, cropped) else Rect(0, 0, w, h)
        folder.clipBounds = crop
        ghost.translationZ = if (opening) -1f else 1f
        ghost.scaleX = 1f
        ghost.scaleY = 1f
        shelf.scaleX = if (opening) m.scale else 1 / m.scale
        shelf.scaleY = shelf.scaleX

        val curve = AccelerateDecelerateInterpolator()
        val ghostTo = if (opening) 1 / m.scale else m.scale
        val shelfMove = shelf.animate().scaleX(1f).scaleY(1f)
        val ghostMove = ghost.animate().scaleX(ghostTo).scaleY(ghostTo)
        for (move in arrayOf(shelfMove, ghostMove)) {
            move.setDuration(ZOOM_MS).setInterpolator(curve).setUpdateListener(null).withLayer()
        }
        (if (opening) shelfMove else ghostMove).setUpdateListener { a ->
            val t = if (opening) a.animatedFraction else 1 - a.animatedFraction
            crop.top = (inset * (1 - t)).toInt()
            crop.bottom = (cropped + (h - cropped) * t).toInt()
            folder.clipBounds = crop
        }
        ghostMove.withEndAction {
            shelf.clipBounds = null
            ghost.clipBounds = null
            hideGhost()
        }
        shelfMove.start()
        ghostMove.start()
    }

    private fun hideGhost() {
        ghost.visibility = View.GONE
        ghost.setImageBitmap(null)
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    // ---- long-press details ----

    private fun showInfo(f: File) {
        if (f.isDirectory) {
            val kids = Library.list(f)
            val folders = kids.count { it.isDirectory }
            MaterialAlertDialogBuilder(this)
                .setTitle(f.name)
                .setMessage(getString(R.string.folder_info, folders, kids.size - folders, f.path))
                .setPositiveButton(R.string.open) { _, _ -> open(f) }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }
        Library.io.execute {
            val meta = Library.meta(applicationContext, f)
            runOnUiThread { if (!isFinishing && !isDestroyed) showBookInfo(f, meta) }
        }
    }

    private fun showBookInfo(f: File, m: BookMeta) {
        val st = Library.state(this, f)
        val favorite = Library.isFavorite(this, f)
        val started = st.lastRead > 0
        val lines = ArrayList<String>()
        lines += "Author: ${m.author.ifBlank { "Unknown" }}"
        lines += "Format: ${f.extension.uppercase()}"
        lines += "Read: ${(st.percent * 100).roundToInt()}%"
        if (Library.isEpub(f)) {
            // EPUBs have no fixed pages; the count is estimated from the amount of text.
            lines += "Pages: about ${(st.percent * m.pages).roundToInt()} of ${m.pages}"
            lines += "Chapter: ${if (started) (st.chapter + 1).coerceAtMost(m.chapters) else 0} of ${m.chapters}"
        } else {
            lines += "Pages: ${if (started) (st.chapter + 1).coerceAtMost(m.pages) else 0} of ${m.pages}"
        }
        lines += "Size: ${Formatter.formatShortFileSize(this, f.length())}"
        lines += "Last read: " +
            if (started) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(st.lastRead))
            else "never"
        lines += "Folder: ${f.parentFile?.path.orEmpty()}"

        MaterialAlertDialogBuilder(this)
            .setTitle(m.title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.open) { _, _ -> open(f) }
            .setNeutralButton(if (favorite) R.string.remove_favorite else R.string.add_favorite) { _, _ ->
                Library.setFavorite(this, f, !favorite)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    companion object {
        private const val KEY_DIR = "dir"
        private const val ZOOM_MS = 560L
    }
}

class ShelfAdapter(
    private val onClick: (File, View) -> Unit,
    private val onLongClick: (File) -> Unit,
    private val spec: () -> ShelfSpec,
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    private var items: List<File> = emptyList()

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cover: RatioImageView = v.findViewById(R.id.cover)
        val star: ImageView = v.findViewById(R.id.star)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val plaque: TextView = v.findViewById(R.id.plaque)
        var file: File? = null
    }

    fun indexOf(f: File) = items.indexOf(f)

    fun submit(list: List<File>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)).also {
            it.plaque.background = PlaqueDrawable(parent.resources.displayMetrics.density)
        }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val f = items[position]
        val ctx = h.itemView.context.applicationContext
        h.file = f
        h.itemView.setOnClickListener { onClick(f, it) }
        h.itemView.setOnLongClickListener {
            onLongClick(f)
            true
        }
        h.cover.binding = !f.isDirectory
        if (f.isDirectory) {
            h.plaque.text = f.name
            h.star.visibility = View.GONE
            h.progress.visibility = View.GONE
            val shelfSpec = spec()
            if (shelfSpec.width <= 0) return
            val preview = Library.cachedFolderPreview(f, shelfSpec)
            h.cover.setImageBitmap(preview)
            if (preview == null) Library.io.execute {
                val bmp = Library.folderPreview(ctx, f, shelfSpec)
                Library.main.post { if (h.file == f) h.cover.setImageBitmap(bmp) }
            }
            return
        }
        val st = Library.state(ctx, f)
        h.star.visibility = if (Library.isFavorite(ctx, f)) View.VISIBLE else View.GONE
        h.progress.visibility = if (st.percent > 0f) View.VISIBLE else View.GONE
        h.progress.progress = (st.percent * 100).roundToInt()
        h.plaque.text = Library.cachedMeta(ctx, f)?.title ?: f.nameWithoutExtension
        val cached = Library.cachedCover(f)
        h.cover.setImageBitmap(cached)
        if (cached == null) Library.io.execute {
            val meta = Library.meta(ctx, f)
            val bmp = Library.cover(ctx, f)
            Library.main.post {
                if (h.file == f) {
                    h.cover.setImageBitmap(bmp)
                    h.plaque.text = meta.title
                }
            }
        }
    }
}

/**
 * Draws the bookcase: a wooden plank under every row of books, empty shelves below the last row,
 * and on top of everything the case's side posts, which stay put while the
 * shelves scroll inside it.
 */
class ShelfDecoration(ctx: Context, private val columns: Int) : RecyclerView.ItemDecoration() {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val bottoms = TreeSet<Int>()
        var row = ShelfSpec(parent.width, columns, d).rowH.coerceAtLeast(1)
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            bottoms.add(v.bottom + v.translationY.toInt())
            if (v.height > 0) row = v.height
        }
        for (b in bottoms) drawPlank(c, parent.width.toFloat(), b.toFloat(), d, paint)
        var b = (if (bottoms.isEmpty()) parent.paddingTop else bottoms.last()) + row
        while (b - (PLANK + LIP) * d < parent.height) {
            drawPlank(c, parent.width.toFloat(), b.toFloat(), d, paint)
            b += row
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        drawCase(c, parent.width.toFloat(), parent.height.toFloat(), ShelfSpec(parent.width, columns, d), paint)
    }

    companion object {
        private const val PLANK = 44
        private const val LIP = 5
        private const val SHADE = 10

        /** One plank whose front face ends at [bottom]; [d] is the display density. */
        fun drawPlank(c: Canvas, w: Float, bottom: Float, d: Float, paint: Paint) {
            val top = bottom - PLANK * d
            gradient(c, w, top - LIP * d, top, 0xFF4A2E1A.toInt(), 0xFFA9743F.toInt(), paint)
            gradient(c, w, top, bottom, 0xFF8B5A2B.toInt(), 0xFF5C3A1E.toInt(), paint)
            // A lit front edge and a dark underside give the board some thickness.
            paint.shader = null
            paint.color = 0x40FFE2B0
            c.drawRect(0f, top, w, top + d, paint)
            paint.color = 0x66000000
            c.drawRect(0f, bottom - 1.5f * d, w, bottom, paint)
            paint.alpha = 255
            gradient(c, w, bottom, bottom + SHADE * d, 0xAA000000.toInt(), 0x00000000, paint)
        }

        /** The case around a shelf area of [w] by [h]: two side posts and the shadows they cast. */
        fun drawCase(c: Canvas, w: Float, h: Float, spec: ShelfSpec, paint: Paint) {
            val post = spec.post.toFloat()
            val shade = 6 * spec.density
            val wood = intArrayOf(0xFF4A2E1A.toInt(), 0xFF9A6836.toInt(), 0xFF5C3A1E.toInt())
            paint.alpha = 255
            for (left in floatArrayOf(0f, w - post)) {
                paint.shader = LinearGradient(left, 0f, left + post, 0f, wood, null, Shader.TileMode.CLAMP)
                c.drawRect(left, 0f, left + post, h, paint)
            }
            // Shadows the posts cast on the shelves beside them.
            paint.shader = LinearGradient(post, 0f, post + shade, 0f, 0x88000000.toInt(), 0, Shader.TileMode.CLAMP)
            c.drawRect(post, 0f, post + shade, h, paint)
            paint.shader =
                LinearGradient(w - post - shade, 0f, w - post, 0f, 0, 0x88000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(w - post - shade, 0f, w - post, h, paint)
            paint.shader = null
        }

        private fun gradient(c: Canvas, w: Float, top: Float, bottom: Float, from: Int, to: Int, paint: Paint) {
            // A shader is still multiplied by the paint alpha, which an earlier translucent colour may have lowered.
            paint.alpha = 255
            paint.shader = LinearGradient(0f, top, 0f, bottom, from, to, Shader.TileMode.CLAMP)
            c.drawRect(0f, top, w, bottom, paint)
        }
    }
}
