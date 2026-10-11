package io.github.edoardonegri.ready

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * A dialog built like the bookcase (dialog_shelf.xml): a wooden panel with a brass title plate,
 * a cross to close it and whatever it is given below: details in gold lettering, a text field, a
 * column of choices, a brass button.
 */
class ShelfDialog(private val ctx: Context) {
    private val dialog = Dialog(ctx)
    private val view: View = LayoutInflater.from(ctx).inflate(R.layout.dialog_shelf, null)
    private val d = ctx.resources.displayMetrics.density
    private val input: EditText = view.findViewById(R.id.dialogInput)
    private val column: LinearLayout = view.findViewById(R.id.dialogOptions)
    private var abandoned = false

    /** What was typed into the field asked for with [input]. */
    val text: String get() = input.text.toString()

    init {
        // The title wears the same plate as the name of the bookcase on its top rail.
        view.findViewById<View>(R.id.dialogTitle).background = PlaqueDrawable(d, ornate = true)
        view.findViewById<View>(R.id.dialogPrimary).background = PlaqueDrawable(d)
        view.findViewById<View>(R.id.dialogClose).setOnClickListener { dialog.dismiss() }
    }

    fun title(text: CharSequence) = apply { view.findViewById<TextView>(R.id.dialogTitle).text = text }

    /** One line per row: the name in gold, then its value. */
    fun details(rows: List<Pair<String, String>>) = apply {
        val gold = ContextCompat.getColor(ctx, R.color.gold)
        val text = SpannableStringBuilder()
        for ((name, value) in rows) {
            if (text.isNotEmpty()) text.append('\n')
            val start = text.length
            text.append(name).append("  ")
            text.setSpan(ForegroundColorSpan(gold), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(value)
        }
        message(text)
    }

    /** A few words in place of the details. */
    fun message(text: CharSequence) = apply {
        view.findViewById<TextView>(R.id.dialogDetails).text = text
        view.findViewById<View>(R.id.dialogBody).visibility = View.VISIBLE
    }

    /** Shows the book standing beside the details. */
    fun cover(bmp: Bitmap?) = apply {
        val cover = view.findViewById<RatioImageView>(R.id.dialogCover)
        cover.binding = true
        cover.setImageBitmap(bmp)
        cover.visibility = View.VISIBLE
    }

    /**
     * Makes the title a field holding [name] that can be typed into. What it holds when the dialog
     * is closed, if it was changed, is given to [onRename] - unless [abandon] was called first.
     */
    fun name(name: String, onRename: (String) -> Unit) = apply {
        val field = view.findViewById<EditText>(R.id.dialogName)
        view.findViewById<View>(R.id.dialogTitle).visibility = View.GONE
        field.visibility = View.VISIBLE
        field.background = PlaqueDrawable(d, ornate = true)
        field.setText(name)
        // Done puts the keyboard away; the name is taken when the dialog closes.
        field.setOnEditorActionListener { v, _, _ ->
            v.clearFocus()
            ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        dialog.setOnDismissListener {
            val typed = field.text.toString().trim()
            if (!abandoned && typed.isNotEmpty() && typed != name) onRename(typed)
        }
    }

    /** Closes the dialog without taking over a changed [name]. */
    fun abandon() {
        abandoned = true
        dialog.dismiss()
    }

    /** An arrow at the top left closes the dialog, in place of the cross. */
    fun arrowCloses() = apply {
        view.findViewById<View>(R.id.dialogClose).visibility = View.GONE
        val arrow = view.findViewById<View>(R.id.dialogBack)
        arrow.visibility = View.VISIBLE
        arrow.setOnClickListener { dialog.dismiss() }
    }

    /** A button at the top right; the dialog stays open unless [onClick] closes it. */
    fun icon(icon: Int, description: Int, onClick: () -> Unit) = apply { addIcon(icon, description).setOnClickListener { onClick() } }

    /** A button at the top right that is switched in place, such as the favorite star. */
    fun switch(on: Boolean, iconOn: Int, iconOff: Int, description: Int, onSwitch: (Boolean) -> Unit) = apply {
        var state = on
        val button = addIcon(if (state) iconOn else iconOff, description)
        button.setOnClickListener {
            state = !state
            button.setImageResource(if (state) iconOn else iconOff)
            onSwitch(state)
        }
    }

    private fun addIcon(icon: Int, description: Int): ImageButton {
        val icons = view.findViewById<LinearLayout>(R.id.dialogIcons)
        val button = LayoutInflater.from(ctx).inflate(R.layout.dialog_icon, icons, false) as ImageButton
        button.setImageResource(icon)
        button.contentDescription = ctx.getString(description)
        icons.addView(button, icons.childCount - 1)
        return button
    }

    /**
     * The wide brass button at the foot as a switch: its wording says how things stand, and
     * pressing it changes that in place, without closing the dialog.
     */
    fun primarySwitch(on: Boolean, whenOn: Int, whenOff: Int, onSwitch: (Boolean) -> Unit) = apply {
        val button = view.findViewById<TextView>(R.id.dialogPrimary)
        val plate = button.background as PlaqueDrawable
        var state = on
        fun show() {
            button.setText(if (state) whenOn else whenOff)
            plate.finished = state
        }
        show()
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            state = !state
            show()
            onSwitch(state)
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
     * A column of brass buttons of which one is chosen, marked with a tick. Choosing another
     * moves the tick and tells [onPick], and the dialog stays open.
     */
    fun pick(labels: List<String>, chosen: Int, onPick: (Int) -> Unit) = apply {
        val buttons = labels.map { option(it) }
        fun mark(index: Int) = buttons.forEachIndexed { i, b -> b.text = (if (i == index) "\u2713  " else "") + labels[i] }
        mark(chosen)
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener {
                mark(i)
                onPick(i)
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
    }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (input.visibility == View.VISIBLE) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.requestFocus()
        }
        dialog.show()
        val width = minOf(ctx.resources.displayMetrics.widthPixels - (40 * d).toInt(), (400 * d).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
