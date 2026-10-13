package io.github.edoardonegri.ready

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
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
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged

/**
 * A dialog built like the bookcase (dialog_shelf.xml): a wooden panel with a brass title plate,
 * a cross to close it and whatever it is given below: details named in gold, a text field, a
 * column of choices, a brass button.
 */
class ShelfDialog(private val ctx: Context) {
    private val dialog = Dialog(ctx)
    // A dialog has no parent to inflate its content into.
    @SuppressLint("InflateParams")
    private val view: View = LayoutInflater.from(ctx).inflate(R.layout.dialog_shelf, null)
    private val d = ctx.resources.displayMetrics.density
    private val input: EditText = view.findViewById(R.id.dialogInput)
    private val column: LinearLayout = view.findViewById(R.id.dialogOptions)

    /** What was typed into the field asked for with [input]. */
    val text: String get() = input.text.toString()

    init {
        // The title wears the same plate as the name of the bookcase on its top rail.
        view.findViewById<View>(R.id.dialogTitle).background = PlaqueDrawable(d, ornate = true)
        view.findViewById<View>(R.id.dialogPrimary).background = PlaqueDrawable(d)
        view.findViewById<View>(R.id.dialogClose).setOnClickListener { dialog.dismiss() }
        // The grain of the board ends at its round corners.
        view.clipToOutline = true
    }

    fun title(text: CharSequence) = apply { view.findViewById<TextView>(R.id.dialogTitle).text = text }

    /**
     * What there is to say about a book or a shelf, two to a line: each with its name in gold
     * over its value. A last one that is left over has the line to itself.
     */
    fun details(rows: List<Pair<String, String>>) = apply {
        val gold = ContextCompat.getColor(ctx, R.color.gold)
        val grid = view.findViewById<LinearLayout>(R.id.dialogGrid)
        grid.visibility = View.VISIBLE
        for (pair in rows.chunked(2)) {
            val line = LinearLayout(ctx)
            line.isBaselineAligned = false
            pair.forEachIndexed { i, (name, value) ->
                val text = SpannableStringBuilder(name).append('\n').append(value)
                text.setSpan(ForegroundColorSpan(gold), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(RelativeSizeSpan(0.85f), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val cell = TextView(ctx)
                cell.text = text
                cell.typeface = Typeface.SERIF
                cell.textSize = 14f
                cell.setTextColor(ContextCompat.getColor(ctx, R.color.cream))
                line.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i > 0) marginStart = (12 * d).toInt() },
                )
            }
            grid.addView(
                line,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (10 * d).toInt() },
            )
        }
    }

    /** A few words in place of the details. */
    fun message(text: CharSequence) = apply {
        val words = view.findViewById<TextView>(R.id.dialogDetails)
        words.text = text
        words.visibility = View.VISIBLE
    }

    /**
     * Makes the title a field holding [name] that can be typed into. What it holds when the dialog
     * is closed, if it was changed, is given to [onRename].
     */
    fun name(name: String, onRename: (String) -> Unit) = apply {
        val field = view.findViewById<EditText>(R.id.dialogName)
        view.findViewById<View>(R.id.dialogTitle).visibility = View.GONE
        field.visibility = View.VISIBLE
        field.background = PlaqueDrawable(d, ornate = true)
        // A long name runs on to a second line, though Enter still means Done, and is written
        // smaller for the two to fit the plate, as the titles that cannot be changed are.
        field.setHorizontallyScrolling(false)
        field.maxLines = 2
        val measure = TextPaint(field.paint).apply { textSize = TITLE_DP * d }
        fun fit() {
            val room = field.width - field.paddingLeft - field.paddingRight
            if (room <= 0) return
            val long = measure.measureText(field.text.toString()) > room
            field.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (long) LONG_TITLE_DP else TITLE_DP) * d)
        }
        field.doOnLayout { fit() }
        field.doAfterTextChanged { fit() }
        field.setText(name)
        // Done puts the keyboard away; the name is taken when the dialog closes.
        field.setOnEditorActionListener { v, _, _ ->
            v.clearFocus()
            ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        dialog.setOnDismissListener {
            val typed = field.text.toString().trim()
            if (typed.isNotEmpty() && typed != name) onRename(typed)
        }
    }

    /** An arrow at the top left closes the dialog, in place of the cross. */
    fun arrowCloses() = apply {
        view.findViewById<View>(R.id.dialogClose).visibility = View.GONE
        val arrow = view.findViewById<View>(R.id.dialogBack)
        arrow.visibility = View.VISIBLE
        arrow.setOnClickListener { dialog.dismiss() }
    }

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
            hold(buttons[0], one)
            hold(buttons[1], !one)
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

    /**
     * Shows the brass [button] as held [down], or as standing out again. Its wording goes down
     * with it, and is bright on the darkened plate.
     */
    private fun hold(button: TextView, down: Boolean) {
        (button.background as PlaqueDrawable).sunk = down
        button.setTextColor(ContextCompat.getColor(ctx, if (down) R.color.gold_light else R.color.plaque_text))
        val shadow = (PlaqueDrawable.SHADOW_DP * d).toInt()
        button.setPadding(0, if (down) shadow else 0, 0, if (down) 0 else shadow)
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
        fun mark(index: Int) = buttons.forEachIndexed { i, b -> hold(b, i == index) }
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

    private fun option(label: String): TextView {
        showColumn(column.childCount + 1)
        val button = TextView(ContextThemeWrapper(ctx, R.style.BrassButton), null, 0, R.style.BrassButton)
        button.text = label
        button.background = PlaqueDrawable(d)
        column.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).toInt())
                .apply { topMargin = (10 * d).toInt() },
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

    private companion object {
        /** The size of the letters of a name on its plate, and of one that takes two lines. */
        const val TITLE_DP = 18
        const val LONG_TITLE_DP = 14
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
        val width = minOf(ctx.resources.displayMetrics.widthPixels - (40 * d).toInt(), (400 * d).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
