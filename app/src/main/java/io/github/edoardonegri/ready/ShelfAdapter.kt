package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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
        var file: File? = null
    }

    fun indexOf(f: File) = items.indexOf(f)

    // The whole shelf is replaced: another folder, another order, or the results of a search.
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>) {
        items = ArrayList(list)
        notifyDataSetChanged()
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
        // What is carried leaves its place empty. Its parts are hidden rather than the place as a
        // whole, which the list makes visible again whenever it moves it.
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
        h.cover.setBackgroundColor(if (f.isFolder) 0 else 0xFF24160D.toInt())
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
            plate.favorite = false
            plate.finished = false
            h.progress.visibility = View.GONE
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
        // A favorite is told by its plate, which leaves the cover free.
        plate.favorite = Library.isFavorite(ctx, f)
        plate.finished = st.percent >= Library.FINISHED_AT
        // A book read to the end has no bar any more: its bronze plate says so. Nor has one still
        // at its first page, which older versions of the app saved as some of the book read.
        val begun = st.chapter > 0 || st.fraction > 0f
        h.progress.visibility = if (begun && st.percent > 0f && !plate.finished) View.VISIBLE else View.GONE
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
