package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.roundToInt

/**
 * The places on a shelf. Tapping one opens it, tapping its plaque shows its details, or, while
 * things are being chosen, lets its name and cover or wood be changed. A long press picks it up.
 */
class ShelfAdapter(
    private val onOpen: (File, View) -> Unit,
    private val onInfo: (File) -> Unit,
    private val onPickUp: (File, View) -> Unit,
    private val spec: () -> ShelfSpec,
) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

    private var items: MutableList<File> = ArrayList()

    /** What stands on the shelf, in the order shown. */
    val files: List<File> get() = items

    /** While several things are being chosen: those chosen so far, which stand out from the rest. */
    var picked: Set<File>? = null

    /** Sets [picked] and shows it on the shelf as it stands, without laying it out anew. */
    fun choose(chosen: Set<File>?) {
        picked = chosen
        notifyItemRangeChanged(0, items.size, "drag")
    }

    /** Things are being carried about: the whole shelf shows in full meanwhile, chosen or not. */
    var moving = false

    /** Shows on each book the shelf it stands on, for books gathered from several shelves. */
    var showPlaces = false

    /** What is being dragged; its places on the shelf stay empty while it is carried about. */
    var carried: Set<File> = emptySet()
        set(value) {
            val changed = field + value
            field = value
            // Said to be a small change, so that the place is not faded over into a new view.
            for (f in changed) indexOf(f).let { if (it >= 0) notifyItemChanged(it, "drag") }
        }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cover: RatioImageView = v.findViewById(R.id.cover)
        val gap: View = v.findViewById(R.id.gap)
        val frame: View = v.findViewById(R.id.coverFrame)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val place: TextView = v.findViewById(R.id.place)
        val plaque: TextView = v.findViewById(R.id.plaque)
        /** What stands in this place now; read by the threads that load its cover, see [onBindViewHolder]. */
        @Volatile
        var file: File? = null
    }

    fun indexOf(f: File) = items.indexOf(f)

    // The whole shelf is replaced: another folder, another order, or the results of a search.
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>) {
        items = ArrayList(list)
        // What was to come into view on the shelf before is not wanted any more.
        aheadRange = IntRange.EMPTY
        aheadAsked++
        notifyDataSetChanged()
    }

    /** The places [loadAhead] was last asked for, and how many times it was asked for others. */
    private var aheadRange = IntRange.EMPTY

    @Volatile
    private var aheadAsked = 0

    /**
     * Loads the covers and miniatures of the places in [range], about to come into view as the
     * shelf is scrolled towards them, once all in view are loaded, for them to show at once. What
     * was asked for before and is not loaded yet is let go of.
     */
    fun loadAhead(ctx: Context, range: IntRange) {
        val wanted = range.first.coerceAtLeast(0)..range.last.coerceAtMost(items.size - 1)
        if (wanted == aheadRange) return
        aheadRange = wanted
        val asked = ++aheadAsked
        val shelfSpec = spec()
        if (shelfSpec.width <= 0) return
        val app = ctx.applicationContext
        for (i in wanted) {
            val f = items[i]
            val loaded = if (f.isFolder) Library.cachedFolderPreview(f, shelfSpec) else Library.cachedCover(f)
            if (loaded == null) Library.ahead {
                if (asked != aheadAsked) return@ahead
                if (f.isFolder) Library.folderPreview(app, f, shelfSpec)
                else {
                    Library.meta(app, f)
                    Library.cover(app, f)
                }
            }
        }
    }

    /** Takes [gone] off the shelf, the others closing up. */
    fun remove(gone: Set<File>) {
        for (f in gone) indexOf(f).let {
            if (it >= 0) {
                items.removeAt(it)
                notifyItemRemoved(it)
            }
        }
    }

    /** Shows [f] anew where it stands, e.g. after it was chosen or let go of. */
    fun changed(f: File) {
        indexOf(f).let { if (it >= 0) notifyItemChanged(it, "drag") }
    }

    /** Takes the item at [from] to the place [to], the others closing up behind it. */
    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    /** What a screen reader says of the book in [h]: its [title] and [author], how much of it is [read], whether it is a favorite or [finished]. */
    private fun describeBook(h: Holder, title: String, author: String, read: Int, favorite: Boolean, finished: Boolean) {
        val res = h.itemView.resources
        val parts = arrayListOf(title)
        if (author.isNotBlank()) parts += res.getString(R.string.a11y_by, author)
        if (finished) parts += res.getString(R.string.a11y_finished) else if (read > 0) parts += res.getString(R.string.a11y_read, read)
        if (favorite) parts += res.getString(R.string.a11y_favorite)
        describe(h, parts.joinToString(", "), title)
    }

    /** Has a screen reader say [what] for the place in [h], and of its plaque that it shows the details of [name]. */
    private fun describe(h: Holder, what: String, name: String) {
        h.itemView.contentDescription = what
        h.plaque.contentDescription = h.itemView.resources.getString(R.string.a11y_details, name)
    }

    /** Whether every place [shelf] shows has its cover or miniature by now, which load in the background. */
    fun allShown(shelf: RecyclerView): Boolean {
        for (i in 0 until shelf.childCount) {
            val h = shelf.getChildViewHolder(shelf.getChildAt(i)) as? Holder ?: continue
            if (h.cover.drawable == null) return false
        }
        return true
    }

    private companion object {
        const val FAINT = 0.4f
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)).also {
            it.plaque.background = PlaqueDrawable(parent.resources.displayMetrics.density)
        }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val f = items[position]
        val ctx = h.itemView.context.applicationContext
        h.file = f
        h.itemView.setOnClickListener { onOpen(f, it) }
        h.plaque.setOnClickListener { onInfo(f) }
        for (v in arrayOf(h.itemView, h.plaque)) v.setOnLongClickListener {
            onPickUp(f, h.itemView)
            true
        }
        // What is carried leaves the place it would be put down in empty: it is only seen in the
        // hand. Its parts are hidden rather than the place as a whole, which the list makes
        // visible again whenever it moves it.
        val carried = f in carried
        val place = h.itemView as ViewGroup
        val faint = !moving && picked.let { it != null && f !in it }
        // A shelf with something chosen on it shows that: its miniature is faint but for that.
        val holds = faint && f.isFolder && picked.orEmpty().any { it.path.startsWith(f.path + File.separator) }
        for (i in 0 until place.childCount) place.getChildAt(i).apply {
            visibility = if (carried) View.INVISIBLE else View.VISIBLE
            alpha = if (faint && !(holds && this === h.frame)) FAINT else 1f
        }
        // A recycled view may have been left enlarged as the shelf something was dragged over.
        h.itemView.scaleX = 1f
        h.itemView.scaleY = 1f
        h.place.visibility = if (showPlaces && !f.isFolder) View.VISIBLE else View.GONE
        if (showPlaces) h.place.text = f.parentFile.let {
            if (it == null || it == Library.root()) ctx.getString(R.string.app_name) else it.name
        }
        val shelfSpec = spec()
        h.cover.binding = !f.isFolder
        h.cover.cased = f.isFolder
        if (f.isFolder) h.cover.wood = Library.wood(ctx, f)
        // A book fills its place; a case rises above the books, through the gap the row leaves
        // at its top, up to the shelf above, and is wider by as much as that makes it larger.
        val rise = if (f.isFolder && shelfSpec.width > 0) shelfSpec.miniRise else 0
        h.cover.scaleType = if (f.isFolder) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.CENTER_CROP
        // A book shows dark leather until its cover comes, which hides it, and is not painted over it for nothing.
        h.cover.setBackgroundColor(if (f.isFolder || Library.cachedCover(f) != null) 0 else 0xFF24160D.toInt())
        val wider = if (rise > 0) (shelfSpec.miniW - shelfSpec.coverW) / 2 else 0
        (h.frame.layoutParams as ViewGroup.MarginLayoutParams).apply {
            leftMargin = -wider
            rightMargin = -wider
        }
        h.cover.ratio =
            if (rise > 0) (shelfSpec.coverH + rise).toFloat() / (shelfSpec.coverW + 2 * wider).coerceAtLeast(1) else ShelfSpec.BOOK
        h.cover.tall = if (shelfSpec.width > 0) shelfSpec.coverH + rise else 0
        h.gap.layoutParams.height = shelfSpec.miniRise - rise
        h.gap.requestLayout()
        val plate = h.plaque.background as PlaqueDrawable
        if (f.isFolder) {
            h.plaque.text = f.name
            describe(h, h.itemView.context.getString(R.string.a11y_shelf, f.name), f.name)
            plate.favorite = false
            plate.finished = false
            h.progress.visibility = View.GONE
            if (shelfSpec.width <= 0) return
            val preview = Library.cachedFolderPreview(f, shelfSpec)
            h.cover.setImageBitmap(preview)
            if (preview == null) Library.shelfWork.execute {
                if (h.file != f) return@execute
                val bmp = Library.folderPreview(ctx, f, shelfSpec)
                Library.main.post { if (h.file == f) h.cover.setImageBitmap(bmp) }
            }
            return
        }
        val st = Library.state(ctx, f)
        // A favorite is told by its plate, which leaves the cover free.
        plate.favorite = Library.isFavorite(ctx, f)
        plate.finished = st.percent >= Library.FINISHED_AT
        // A book read to the end has no bar any more: its bronze plate says so. Nor has one still
        // at its first page, which older versions of the app saved as some of the book read.
        val begun = st.chapter > 0 || st.fraction > 0f
        h.progress.visibility = if (begun && st.percent > 0f && !plate.finished) View.VISIBLE else View.GONE
        h.progress.progress = (st.percent * 100).roundToInt()
        val known = Library.cachedMeta(ctx, f)
        h.plaque.text = known?.title ?: f.nameWithoutExtension
        val read = if (h.progress.isVisible) (st.percent * 100).roundToInt() else 0
        describeBook(h, known?.title ?: f.nameWithoutExtension, known?.author.orEmpty(), read, plate.favorite, plate.finished)
        val cached = Library.cachedCover(f)
        h.cover.setImageBitmap(cached)
        if (cached == null) Library.shelfWork.execute {
            // Scrolled past before its turn came: it is loaded when it is shown again.
            if (h.file != f) return@execute
            val meta = Library.meta(ctx, f)
            val bmp = Library.cover(ctx, f)
            Library.main.post {
                if (h.file == f) {
                    h.cover.setImageBitmap(bmp)
                    if (bmp != null) h.cover.setBackgroundColor(0)
                    h.plaque.text = meta.title
                    describeBook(h, meta.title, meta.author, read, plate.favorite, plate.finished)
                }
            }
        }
    }
}
