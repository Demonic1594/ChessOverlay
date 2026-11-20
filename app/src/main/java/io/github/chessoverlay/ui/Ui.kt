package io.github.chessoverlay.ui

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** Small helpers for building the settings screens in code. */
fun Activity.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun Activity.heading(parent: LinearLayout, text: String) {
    parent.addView(TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18f), 0, dp(6f))
    })
}

fun Activity.note(parent: LinearLayout, text: String): TextView =
    TextView(this).apply { this.text = text; textSize = 13f; alpha = 0.75f }.also { parent.addView(it) }

fun Activity.row(parent: LinearLayout, label: String, control: View) {
    val r = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4f), 0, dp(4f))
    }
    r.addView(TextView(this).apply { text = label; textSize = 15f },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    r.addView(control)
    parent.addView(r)
}

/** A drop-down of labelled choices; `onPick` gets the chosen index. */
fun Activity.choice(labels: List<String>, selected: Int, onPick: (Int) -> Unit): Spinner =
    Spinner(this).apply {
        adapter = ArrayAdapter(this@choice, android.R.layout.simple_spinner_dropdown_item, labels)
        setSelection(selected.coerceIn(0, maxOf(0, labels.size - 1)))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

@Suppress("UseSwitchCompatOrMaterialCode")
fun Activity.toggle(on: Boolean, onChange: (Boolean) -> Unit): Switch =
    Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, b -> onChange(b) } }
