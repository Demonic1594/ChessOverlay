package io.github.chessoverlay.ui

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.chessoverlay.R

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

/** "SmotherWinTimePercent" may break between its words ("Smother·Win·Time·Percent"), not mid-word. */
fun breakable(name: String): String = name.replace(Regex("(?<=[a-z0-9])(?=[A-Z])|_"), "$0\u200B")

/**
 * The building blocks of the settings screens, so every setting looks and
 * behaves the same: a switch row, a row of choice chips, an action row, a
 * checklist row. Rows in one card are separated by a hairline.
 */
class Rows(private val parent: LinearLayout) {
    private val ctx = parent.context
    private val inflater = LayoutInflater.from(ctx)

    private fun separate() {
        if (parent.childCount == 0) return
        parent.addView(View(ctx).apply { setBackgroundColor(ContextCompat.getColor(ctx, R.color.outline)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, ctx.dp(0.7f))).apply {
                topMargin = ctx.dp(4f); bottomMargin = ctx.dp(4f)
            })
    }

    private fun caption(v: View, text: String?) {
        val c = v.findViewById<TextView>(R.id.caption)
        if (text.isNullOrEmpty()) c.visibility = View.GONE else { c.text = text; c.visibility = View.VISIBLE }
    }

    fun switch(title: String, caption: String?, on: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        separate()
        val v = inflater.inflate(R.layout.row_switch, parent, false)
        v.findViewById<TextView>(R.id.title).text = title
        caption(v, caption)
        val s = v.findViewById<MaterialSwitch>(R.id.toggle)
        s.isChecked = on
        s.setOnCheckedChangeListener { _, b -> onChange(b) }
        v.setOnClickListener { s.toggle() }
        parent.addView(v)
        return s
    }

    fun chips(title: String, caption: String?, labels: List<String>, selected: Int, onPick: (Int) -> Unit): ChipGroup {
        separate()
        val v = inflater.inflate(R.layout.row_chips, parent, false)
        v.findViewById<TextView>(R.id.title).text = title
        caption(v, caption)
        val g = v.findViewById<ChipGroup>(R.id.chips)
        val ids = labels.map { label ->
            Chip(ctx).apply {
                text = label
                isCheckable = true
                id = View.generateViewId()
            }.also { g.addView(it) }.id
        }
        g.check(ids[selected.coerceIn(0, ids.size - 1)])
        g.setOnCheckedStateChangeListener { _, checked ->
            val i = ids.indexOf(checked.firstOrNull() ?: return@setOnCheckedStateChangeListener)
            if (i >= 0) onPick(i)
        }
        parent.addView(v)
        return g
    }

    fun action(icon: Int, title: String, caption: String?, onClick: () -> Unit): View {
        separate()
        val v = inflater.inflate(R.layout.row_action, parent, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).text = title
        caption(v, caption)
        v.setOnClickListener { onClick() }
        parent.addView(v)
        return v
    }

    /** A text field (numbers with `numeric`); `onDone` gets the text as typed, on every change. */
    fun input(title: String, caption: String?, value: String, numeric: Boolean, wide: Boolean,
              onChange: (String) -> Unit): com.google.android.material.textfield.TextInputLayout {
        separate()
        val v = inflater.inflate(R.layout.row_input, parent, false)
        v.findViewById<TextView>(R.id.title).text = title
        caption(v, caption)
        val field = v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.field)
        val input = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input)
        if (wide) {
            // Long values (file paths) get the whole width, under the title.
            (v as LinearLayout).orientation = LinearLayout.VERTICAL
            field.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6f) }
            (v.getChildAt(0).layoutParams as LinearLayout.LayoutParams).width = LinearLayout.LayoutParams.MATCH_PARENT
        }
        input.setText(value)
        if (numeric) input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = onChange(s.toString())
        })
        parent.addView(v)
        return field
    }

    /** Takes every row out (the rows are built again). */
    fun clear() = parent.removeAllViews()

    /** A heading inside a card, above some of its rows. */
    fun subhead(text: String) {
        parent.addView(TextView(ctx, null, 0, R.style.Caption).apply {
            this.text = text
            setPadding(ctx.dp(4f), ctx.dp(10f), ctx.dp(4f), 0)
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** A row of buttons of equal width (`labels` short: one line each). */
    fun buttons(labels: List<String>, onClick: (Int) -> Unit) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, label) in labels.withIndex()) {
            val button = MaterialButton(ctx, null, R.attr.tonalButtonStyle).apply {
                text = label
                maxLines = 1
                setPadding(ctx.dp(8f), paddingTop, ctx.dp(8f), paddingBottom)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i < labels.size - 1) marginEnd = ctx.dp(8f)
                }
                setOnClickListener { onClick(i) }
            }
            row.addView(button)
        }
        parent.addView(row)
    }

    /** What something needs: done (a tick), to do (a warning sign; tapping it does what is needed) or busy. */
    class Status(val label: String, val done: Boolean, val busy: Boolean = false, val onClick: (() -> Unit)? = null)

    /** The needs of the overlay as chips in a row. */
    fun statusChips(items: List<Status>) {
        for ((i, s) in items.withIndex()) {
            val chip = Chip(ctx, null, R.attr.statusChipStyle).apply {
                text = s.label
                isChipIconVisible = !s.busy
                chipIcon = ContextCompat.getDrawable(ctx, if (s.done) R.drawable.ic_check else R.drawable.ic_warn)
                chipIconTint = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, if (s.done) R.color.good else R.color.warn))
                isClickable = s.onClick != null
                s.onClick?.let { action -> setOnClickListener { action() } }
            }
            parent.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = ctx.dp(6f)
            })
        }
    }

    fun check(icon: Int, title: String): CheckRow {
        separate()
        val v = inflater.inflate(R.layout.row_check, parent, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).text = title
        parent.addView(v)
        return CheckRow(v)
    }
}

/** One line of the setup checklist: done (green tick), to do (amber, with an action), or busy. */
class CheckRow(private val v: View) {
    enum class State { OK, TODO, BUSY }

    fun set(state: State, detail: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val ctx = v.context
        v.findViewById<TextView>(R.id.detail).text = detail
        val icon = v.findViewById<ImageView>(R.id.state)
        when (state) {
            State.OK -> { icon.setImageResource(R.drawable.ic_check); icon.setColorFilter(ContextCompat.getColor(ctx, R.color.good)) }
            State.TODO -> { icon.setImageResource(R.drawable.ic_warn); icon.setColorFilter(ContextCompat.getColor(ctx, R.color.warn)) }
            State.BUSY -> icon.setImageDrawable(null)
        }
        val b = v.findViewById<MaterialButton>(R.id.action)
        if (action != null && onAction != null) {
            b.text = action
            b.visibility = View.VISIBLE
            b.setOnClickListener { onAction() }
            icon.visibility = View.GONE
        } else {
            b.visibility = View.GONE
            icon.visibility = View.VISIBLE
        }
    }
}
