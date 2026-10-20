package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible

/**
 * A dialog built like the bookcase (dialog_shelf.xml): a wooden panel with a brass title plate,
 * a cross to close it and whatever it is given below: details named in gold, a picture with
 * fields beside it, a text field, a column of choices or a drop-down menu, a brass button.
 */
class ShelfDialog(private val ctx: Context) {
    private val dialog = Dialog(ctx)
    // A dialog has no parent to inflate its content into.
    @SuppressLint("InflateParams")
    private val view: View = LayoutInflater.from(ctx).inflate(R.layout.dialog_shelf, null)
    private val d = ctx.resources.displayMetrics.density

    private fun dp(v: Int) = (v * d).toInt()
    private val input: EditText = view.findViewById(R.id.dialogInput)
    private val column: LinearLayout = view.findViewById(R.id.dialogOptions)

    /** What was typed into the field asked for with [input]. */
    val text: String get() = input.text.toString()

    init {
        // The title wears the same plate as the name of the bookcase on its top rail.
        view.findViewById<View>(R.id.dialogTitle).background = PlaqueDrawable(d, ornate = true)
        view.findViewById<View>(R.id.dialogPrimary).background = PlaqueDrawable(d)
        view.findViewById<View>(R.id.dialogClose).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { for (action in closing) action() }
        // The grain of the board ends at its round corners.
        view.clipToOutline = true
    }

    fun title(text: CharSequence) = apply { view.findViewById<TextView>(R.id.dialogTitle).text = text }

    /**
     * What there is to say about a book or a shelf, in two columns: each with its name in gold
     * over its value, which stays in its own column however long it is. They go two to a line,
     * or, [byColumn], down the left column first and then down the right one, so that a long
     * value on the right does not push the next on the left further down.
     */
    fun details(rows: List<Pair<String, String>>, byColumn: Boolean = false) = apply {
        val grid = view.findViewById<LinearLayout>(R.id.dialogGrid)
        grid.visibility = View.VISIBLE
        fun wide(gap: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = gap }
        if (byColumn) {
            val line = LinearLayout(ctx)
            line.isBaselineAligned = false
            rows.chunked((rows.size + 1) / 2).forEachIndexed { i, column ->
                val stack = LinearLayout(ctx)
                stack.orientation = LinearLayout.VERTICAL
                for (row in column) stack.addView(detail(row), wide(dp(10)))
                line.addView(stack, column(i))
            }
            grid.addView(line, wide(0))
            return@apply
        }
        for (pair in rows.chunked(2)) {
            val line = LinearLayout(ctx)
            line.isBaselineAligned = false
            // A last one left over keeps to the left column, with the right one empty.
            line.weightSum = 2f
            pair.forEachIndexed { i, row -> line.addView(detail(row), column(i)) }
            grid.addView(line, wide(dp(10)))
        }
    }

    /** The place of the column [i] of the details, 0 on the left. */
    private fun column(i: Int) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { if (i > 0) marginStart = dp(12) }

    /** One of the details: its name in gold over its value. */
    private fun detail(row: Pair<String, String>): TextView {
        val (name, value) = row
        val text = SpannableStringBuilder(name).append('\n').append(value)
        text.setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.gold)), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(0.85f), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val cell = TextView(ctx)
        cell.text = text
        cell.typeface = Typeface.SERIF
        cell.textSize = 14f
        cell.setTextColor(ContextCompat.getColor(ctx, R.color.cream))
        return cell
    }

    /** A few words in place of the details. */
    fun message(text: CharSequence) = apply {
        val words = view.findViewById<TextView>(R.id.dialogDetails)
        words.text = text
        words.visibility = View.VISIBLE
    }

    /** What is to be done when the dialog closes, however it is closed. */
    private val closing = ArrayList<() -> Unit>()

    /** Runs [action] when the dialog closes, after what was given before. */
    fun onClose(action: () -> Unit) = apply { closing += action }

    /** The picture of a book or a shelf, at the left of the fields that change it. */
    val picture: RatioImageView by lazy {
        view.findViewById<View>(R.id.dialogForm).visibility = View.VISIBLE
        view.findViewById(R.id.dialogPicture)
    }

    private val fields: LinearLayout by lazy {
        view.findViewById<View>(R.id.dialogForm).visibility = View.VISIBLE
        view.findViewById(R.id.dialogFields)
    }

    /** The name of a field, in gold over it. */
    private fun fieldLabel(label: Int): TextView {
        val name = TextView(ctx).apply {
            setText(label)
            typeface = Typeface.SERIF
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.gold))
            if (fields.isNotEmpty()) setPadding(0, dp(10), 0, 0)
        }
        fields.addView(name)
        return name
    }

    /** A field named [label] holding [value], which can be typed into; read it once the dialog closes, see [onClose]. */
    fun field(label: Int, value: String): EditText {
        fieldLabel(label)
        val field = EditText(ctx)
        field.setText(value)
        field.background = ContextCompat.getDrawable(ctx, R.drawable.field)
        field.typeface = Typeface.SERIF
        field.textSize = 15f
        field.setTextColor(ContextCompat.getColor(ctx, R.color.cream))
        field.setPadding(dp(10), 0, dp(10), 0)
        field.isSingleLine = true
        field.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS
        if (Build.VERSION.SDK_INT >= 26) field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        // Done puts the keyboard away; what was typed is taken when the dialog closes.
        field.setOnEditorActionListener { v, _, _ ->
            v.clearFocus()
            ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        fields.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        // The keyboard pushes the dialog up rather than covering the field. A dialog's window
        // still needs the old way to be told so: it draws no insets of its own.
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return field
    }

    /** A drop-down menu named [label] among the fields; [onPick] is told what is picked in it. */
    fun choice(label: Int, onPick: (Int) -> Unit): BrassDropdown {
        fieldLabel(label)
        val menu = BrassDropdown(ctx, onPick)
        fields.addView(menu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        return menu
    }

    /** Like [choice], with the name of the field given too, to be shown or hidden along with the menu. */
    fun namedChoice(label: Int, onPick: (Int) -> Unit): Pair<View, BrassDropdown> {
        val name = fieldLabel(label)
        val menu = BrassDropdown(ctx, onPick)
        fields.addView(menu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        return name to menu
    }

    /** A drop-down menu of [labels] in place of a column of choices, with [selected] picked; [onPick] is told what is picked. */
    fun dropdown(labels: List<String>, selected: Int, onPick: (Int) -> Unit) = apply {
        showColumn(1)
        // What was picked may have changed which of the pair at the foot is held down.
        val menu = BrassDropdown(ctx) {
            onPick(it)
            showPair()
        }
        menu.show(labels, selected)
        column.addView(
            menu,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(10) },
        )
    }

    /** Whether the dialog is still open. */
    val isShowing get() = dialog.isShowing

    /** The dialog has no cross: it is closed by a tap beside it, or by going back. */
    fun uncrossed() = apply { view.findViewById<View>(R.id.dialogClose).visibility = View.GONE }

    /** Shows anew which of the pair at the foot is held down, see [either]. */
    private var showPair = {}

    /**
     * Two buttons side by side under a line, [first] and [second], of which one is held down:
     * the first if [isFirst] says so. Pressing the other tells [onPick], and the dialog stays open.
     */
    fun either(first: Int, second: Int, isFirst: () -> Boolean, onPick: (first: Boolean) -> Unit) = apply {
        val buttons = listOf(R.id.dialogFirst to first, R.id.dialogSecond to second).map { (id, label) ->
            view.findViewById<TextView>(id).apply {
                setText(label)
                background = PlaqueDrawable(d)
            }
        }
        showPair = {
            val one = isFirst()
            buttons[0].holdBrass(one)
            buttons[1].holdBrass(!one)
        }
        showPair()
        view.findViewById<View>(R.id.dialogRule).visibility = View.VISIBLE
        view.findViewById<View>(R.id.dialogPair).visibility = View.VISIBLE
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener {
                onPick(i == 0)
                showPair()
            }
        }
    }

    /** A text field holding [initial], opened with the keyboard up; read it with [text]. */
    fun input(hint: Int, initial: String = "") = apply {
        input.setHint(hint)
        input.setText(initial)
        input.setSelection(initial.length)
        input.visibility = View.VISIBLE
    }

    /** An arrow beside the title that closes this dialog and goes back to the one it was opened from. */
    fun back(action: () -> Unit) = apply {
        val arrow = view.findViewById<View>(R.id.dialogBack)
        arrow.visibility = View.VISIBLE
        arrow.setOnClickListener {
            dialog.dismiss()
            action()
        }
    }

    /** A column of brass buttons; each closes the dialog and runs its action. */
    fun options(choices: List<Pair<String, () -> Unit>>) = apply {
        for ((label, action) in choices) {
            option(label).setOnClickListener {
                dialog.dismiss()
                action()
            }
        }
    }

    /**
     * A column of brass buttons of which one is chosen: it is held down. Choosing another lets
     * it go, holds that one down and tells [onPick], and the dialog stays open.
     */
    fun pick(labels: List<String>, chosen: Int, onPick: (Int) -> Unit) = apply {
        val buttons = labels.map { option(it) }
        fun mark(index: Int) = buttons.forEachIndexed { i, b -> b.holdBrass(i == index) }
        mark(chosen)
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener {
                mark(i)
                onPick(i)
                // What was picked may have changed which of the pair at the foot is held down.
                showPair()
            }
        }
    }

    /** A few words between the buttons of the column, in the cream of the details. */
    fun note(text: CharSequence) = apply {
        showColumn(buttons)
        val words = TextView(ctx)
        words.text = text
        words.typeface = Typeface.SERIF
        words.textSize = 14f
        words.setLineSpacing(dp(4).toFloat(), 1f)
        words.setTextColor(ContextCompat.getColor(ctx, R.color.cream))
        column.addView(
            words,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(if (column.childCount == 0) 6 else 16) },
        )
    }

    /** A brass button in the column that closes the dialog and runs [action]. */
    fun button(label: String, action: () -> Unit) = apply {
        option(label).setOnClickListener {
            dialog.dismiss()
            action()
        }
    }

    /** How many buttons the column has, which is what makes it scroll; the words between them do not count. */
    private var buttons = 0

    private fun option(label: String): TextView {
        showColumn(++buttons)
        val button = TextView(ContextThemeWrapper(ctx, R.style.BrassButton), null, 0, R.style.BrassButton)
        button.text = label
        button.background = PlaqueDrawable(d)
        column.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
                .apply { topMargin = dp(10) },
        )
        return button
    }

    /** Shows the column of choices; one of more than a handful scrolls in half the screen's height. */
    private fun showColumn(count: Int) {
        val scroll = view.findViewById<View>(R.id.dialogScroll)
        scroll.visibility = View.VISIBLE
        scroll.layoutParams.height =
            if (count > 7) ctx.resources.displayMetrics.heightPixels / 2 else ViewGroup.LayoutParams.WRAP_CONTENT
    }

    /** The wide brass button at the foot; it closes the dialog and runs [action]. */
    fun primary(text: Int, action: () -> Unit) = apply {
        val button = view.findViewById<TextView>(R.id.dialogPrimary)
        button.setText(text)
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            dialog.dismiss()
            action()
        }
        // Done on the keyboard of a field is the same as the button.
        input.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            done.also { if (it) button.performClick() }
        }
    }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        if (input.isVisible) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.requestFocus()
        }
        dialog.show()
        val width = minOf(ctx.resources.displayMetrics.widthPixels - dp(40), dp(400))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/**
 * Shows this brass button as held [down], its plate turned to bronze, or as standing out again.
 * Its background must be a [PlaqueDrawable].
 */
fun TextView.holdBrass(down: Boolean) {
    (background as PlaqueDrawable).sunk = down
}

/**
 * A drop-down menu in brass: the choice made stands on a plate like a brass button's, with an
 * arrow engraved at either end, and the choices open on a wooden board that hangs from the foot
 * of the plate, each in its own typeface if [show] is given some. [onPick] is told whenever
 * another is picked.
 */
@SuppressLint("ViewConstructor")
class BrassDropdown(ctx: Context, private val onPick: (Int) -> Unit) : AppCompatTextView(ctx) {
    private val d = ctx.resources.displayMetrics.density

    private fun dp(v: Int) = (v * d).toInt()
    private val plate = PlaqueDrawable(d).apply { arrows = 1 }
    private var labels: List<String> = emptyList()
    private var faces: List<Typeface?>? = null

    /** The choice made. */
    private var chosen = -1

    /** The board of choices while it is open. */
    private var board: PopupWindow? = null

    /** How many choices there are. */
    val count get() = labels.size

    init {
        background = plate
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        textSize = 15f
        setTextColor(ContextCompat.getColor(ctx, R.color.plaque_text))
        // Clear of the arrows at its ends.
        val side = ((PlaqueDrawable.ARROW_X_DP + 10) * d).toInt()
        setPadding(side, 0, side, (PlaqueDrawable.SHADOW_DP * d).toInt())
        setOnClickListener { open() }
    }

    /** Offers [labels], written in [faces] where given, with [selected] picked. */
    fun show(labels: List<String>, selected: Int, faces: List<Typeface?>? = null) {
        this.labels = labels
        this.faces = faces
        select(selected)
    }

    /** Shows [index] as picked, without telling [onPick]. */
    fun select(index: Int) {
        chosen = index
        text = labels.getOrNull(index).orEmpty()
        typeface = Typeface.create(faces?.getOrNull(index) ?: Typeface.SERIF, Typeface.BOLD)
    }

    /**
     * Opens the board of choices under the plate, as wide as it is. It always hangs down: where
     * there is not room for all the choices below, it holds as many as fit, and scrolls.
     */
    private fun open() {
        if (labels.isEmpty() || board != null) return
        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        labels.forEachIndexed { i, label ->
            list.addView(TextView(context).apply {
                text = label
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                textSize = 15f
                val made = i == chosen
                typeface = Typeface.create(faces?.getOrNull(i) ?: Typeface.SERIF, if (made) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(context, if (made) R.color.gold_light else R.color.cream))
                val side = dp(16)
                setPadding(side, dp(12), side, dp(12))
                setOnClickListener {
                    board?.dismiss()
                    if (i == chosen) return@setOnClickListener
                    select(i)
                    onPick(i)
                }
            })
        }
        val scroll = ScrollView(context)
        scroll.isVerticalScrollBarEnabled = false
        scroll.setBackgroundResource(R.drawable.panel_wood)
        // The grain of the board ends at its round corners.
        scroll.clipToOutline = true
        scroll.addView(list)
        // The room from the foot of the plate down to the foot of the screen.
        val screen = Rect()
        getWindowVisibleDisplayFrame(screen)
        val at = IntArray(2)
        getLocationOnScreen(at)
        val room = screen.bottom - (at[1] + height) - dp(8)
        scroll.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        val tall = scroll.measuredHeight.coerceAtMost(room.coerceAtLeast(dp(48)))
        board = PopupWindow(scroll, width, tall, true).apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            elevation = 8 * d
            isOutsideTouchable = true
            setOnDismissListener {
                board = null
                plate.arrows = 1
            }
            showAsDropDown(this@BrassDropdown, 0, 0)
        }
        plate.arrows = -1
        // The choice made is in view, even far down a long list.
        scroll.post { list.getChildAt(chosen)?.let { scroll.scrollTo(0, (it.top - tall / 3).coerceAtLeast(0)) } }
    }

    override fun onDetachedFromWindow() {
        board?.dismiss()
        super.onDetachedFromWindow()
    }
}
