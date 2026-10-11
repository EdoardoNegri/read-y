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
 * The places on a shelf. Tapping one opens it, tapping its plaque shows its details, and a long
 * press picks it up.
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

    /** Shows on each book the shelf it stands on, for books gathered from several shelves. */
    var showPlaces = false

    /** The item being dragged; its place on the shelf stays empty while it is carried about. */
    var dragging: File? = null
        set(value) {
            val changed = listOfNotNull(field, value)
            field = value
            // Said to be a small change, so that the place is not faded over into a new view.
            for (f in changed) indexOf(f).let { if (it >= 0) notifyItemChanged(it, "drag") }
        }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cover: RatioImageView = v.findViewById(R.id.cover)
        val gap: View = v.findViewById(R.id.gap)
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

    /** Takes the item at [from] to the place [to], the others closing up behind it. */
    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
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
        h.itemView.setOnClickListener { onOpen(f, it) }
        h.plaque.setOnClickListener { onInfo(f) }
        for (v in arrayOf(h.itemView, h.plaque)) v.setOnLongClickListener {
            onPickUp(f, h.itemView)
            true
        }
        // What is carried leaves its place empty. Its parts are hidden rather than the place as a
        // whole, which the list makes visible again whenever it moves it.
        val carried = f == dragging
        val place = h.itemView as ViewGroup
        val faint = picked.let { it != null && f !in it }
        for (i in 0 until place.childCount) place.getChildAt(i).apply {
            visibility = if (carried) View.INVISIBLE else View.VISIBLE
            alpha = if (faint) 0.35f else 1f
        }
        // A recycled view may have been left enlarged as the shelf something was dragged over.
        h.itemView.scaleX = 1f
        h.itemView.scaleY = 1f
        h.place.visibility = if (showPlaces && !Library.isShelf(f)) View.VISIBLE else View.GONE
        if (showPlaces) h.place.text = f.parentFile.let {
            if (it == null || it == Library.root()) ctx.getString(R.string.app_name) else it.name
        }
        val shelfSpec = spec()
        h.cover.binding = !Library.isShelf(f)
        // A book fills its place; a case is narrower, stands in the middle of it and rises above
        // the books into the gap under the shelf above, which the row's top padding gives up.
        val rise = if (Library.isShelf(f) && shelfSpec.width > 0) shelfSpec.miniRise else 0
        h.cover.scaleType = if (Library.isShelf(f)) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        h.cover.setBackgroundColor(if (Library.isShelf(f)) 0 else 0xFF24160D.toInt())
        h.cover.ratio = 1.5f + if (rise > 0) rise.toFloat() / shelfSpec.coverW else 0f
        h.gap.layoutParams.height = shelfSpec.px(16) - rise
        h.gap.requestLayout()
        val plate = h.plaque.background as PlaqueDrawable
        if (Library.isShelf(f)) {
            h.plaque.text = if (f == Library.favoritesShelf()) ctx.getString(R.string.favorites) else f.name
            // The shelf of the favorites wears their plate.
            plate.favorite = f == Library.favoritesShelf()
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
        plate.finished = Library.isFinished(ctx, f)
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
