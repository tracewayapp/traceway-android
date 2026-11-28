package com.tracewayapp.fieldmart.checkout

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tracewayapp.fieldmart.FieldmartApplication
import com.tracewayapp.fieldmart.FmIds
import com.tracewayapp.fieldmart.MainActivity
import com.tracewayapp.fieldmart.data.Promo
import com.tracewayapp.fieldmart.dp
import com.tracewayapp.fieldmart.money
import com.tracewayapp.fieldmart.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Checkout (02-APP-SPEC.md §2.5), Views: one long form in four sections of standard Material controls. The card
 * number is masked (blur) and the CVV blanked through the Telemetry seam; the loyalty number uses the
 * `traceway-mask` tag in the layout, so both routes are exercised.
 */
class CheckoutFragment : Fragment() {
    private val app by lazy { FieldmartApplication.of(requireContext()) }
    private val quantities = intArrayOf(1, 1, 1)
    private val itemTitles = arrayOf("Item 1", "Item 2", "Item 3")
    private val itemPrices = longArrayOf(1999, 2999, 999)
    private var rating = 0
    private var instruction = "none"
    private var agreed = false
    private var date = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fm_checkout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val telemetry = app.telemetry
        telemetry.mask(view.findViewById(R.id.fm_checkout_card), blank = false)
        telemetry.mask(view.findViewById(R.id.fm_checkout_cvv), blank = true)

        val passwordBox = view.findViewById<TextInputLayout>(R.id.fm_til_password)
        view.findViewById<MaterialSwitch>(R.id.fm_checkout_account).setOnCheckedChangeListener { _, on ->
            passwordBox.visibility = if (on) View.VISIBLE else View.GONE
        }
        val giftBox = view.findViewById<TextInputLayout>(R.id.fm_til_giftnote)
        view.findViewById<MaterialCheckBox>(R.id.fm_checkout_gift).setOnCheckedChangeListener { _, on ->
            giftBox.visibility = if (on) View.VISIBLE else View.GONE
        }
        val sections = resources.getStringArray(R.array.fm_l_checkout_sections)
        val windows = resources.getStringArray(R.array.fm_l_checkout_windows)
        for (i in 0 until 4) view.findViewWithTag<TextView>("fm-section-$i")?.text = sections[i]
        for (i in 0 until 3) view.findViewWithTag<TextView>("fm-window-$i")?.text = windows[i]
        val tipValue = view.findViewById<TextView>(R.id.fm_checkout_tip_value)
        val tipText = getString(R.string.fm_l_checkout_tipValue)
        tipValue.text = tipText.replace("{n}", "0")
        view.findViewById<Slider>(R.id.fm_checkout_tip).addOnChangeListener { _, value, _ -> tipValue.text = tipText.replace("{n}", value.toInt().toString()) }
        view.findViewById<TextView>(R.id.fm_checkout_instructions).text = ""

        setUpCountry(view.findViewById(R.id.fm_checkout_country))
        setUpDate(view.findViewById(R.id.fm_checkout_date))
        setUpPromo(view, view.findViewById(R.id.fm_checkout_promo))
        setUpRating(view.findViewById(R.id.fm_checkout_rating))
        view.findViewById<View>(R.id.fm_checkout_terms).setOnClickListener { showTerms() }
        view.findViewById<ImageButton>(R.id.fm_checkout_menu).setOnClickListener { showMenu(it) }
        view.findViewById<View>(R.id.fm_checkout_edit).setOnClickListener {
            EditItemsSheet().show(childFragmentManager, "edit")
        }
        view.findViewById<View>(R.id.fm_checkout_place).setOnClickListener { place(view) }
        viewLifecycleOwner.lifecycleScope.launch {
            terms = withContext(Dispatchers.IO) { runCatching { app.api.terms() }.getOrDefault("") }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ids = intArrayOf(17, 3, 42)
            val found = withContext(Dispatchers.IO) { ids.map { runCatching { app.api.product(it).product }.getOrNull() } }
            found.forEachIndexed { i, p ->
                if (p != null) {
                    itemTitles[i] = p.title
                    itemPrices[i] = p.priceCents
                }
            }
            renderSummary()
        }
        renderSummary()
    }

    /** The country picker: a dialog with the 200 countries, as many shop apps show it. */
    private fun setUpCountry(field: TextInputEditText) {
        var countries: List<String> = emptyList()
        viewLifecycleOwner.lifecycleScope.launch {
            countries = withContext(Dispatchers.IO) { runCatching { app.api.countries() }.getOrDefault(emptyList()) }
        }
        field.setOnClickListener {
            val items = countries.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.fm_l_checkout_countryPicker)
                .setItems(items) { _, which -> field.setText(items[which]) }
                .show()
        }
    }

    private fun setUpDate(field: TextInputEditText) {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        field.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker().setTitleText(getString(R.string.fm_l_checkout_datePicker)).build()
            picker.addOnPositiveButtonClickListener { utc ->
                date = format.format(Date(utc))
                field.setText(date)
            }
            picker.show(childFragmentManager, "date")
        }
    }

    private fun setUpPromo(root: View, field: MaterialAutoCompleteTextView) {
        val adapter = PromoAdapter()
        field.setAdapter(adapter)
        field.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val promo = adapter.getItem(position) ?: return@OnItemClickListener
            field.setText(promo.code, false)
            Snackbar.make(root, promo.label, 3000).show()
        }
    }

    private fun setUpRating(row: LinearLayout) {
        for (n in 1..5) {
            row.addView(ImageButton(requireContext()).apply {
                FmIds.set(this, "fm-checkout-rating-$n")
                setImageResource(R.drawable.fm_ic_star)
                contentDescription = "$n"
                setBackgroundResource(android.R.color.transparent)
                layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
                setOnClickListener {
                    rating = n
                    paintStars(row)
                }
            })
        }
        paintStars(row)
    }

    private fun paintStars(row: LinearLayout) {
        for (i in 0 until row.childCount) {
            (row.getChildAt(i) as ImageButton).imageTintList =
                ColorStateList.valueOf(if (i < rating) 0xFFF2A541.toInt() else 0xFFD1D5DB.toInt())
        }
    }

    private var terms: String = ""

    private fun showTerms() {
        val text = TextView(requireContext()).apply {
            text = terms
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
        }
        val scroll = NestedScrollView(requireContext()).apply {
            id = R.id.fm_checkout_terms_text
            addView(text)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fm_l_checkout_terms)
            .setView(scroll)
            .setPositiveButton(R.string.fm_l_checkout_agree) { _, _ -> agreed = true }
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).id = R.id.fm_checkout_terms_agree
    }

    /** The delivery instructions overflow menu: a ListPopupWindow (what PopupMenu shows), whose rows carry ids. */
    private fun showMenu(anchor: View) {
        val items = resources.getStringArray(R.array.fm_l_checkout_menuItems).toList()
        val popup = ListPopupWindow(requireContext())
        popup.anchorView = anchor
        popup.width = requireContext().dp(240)
        popup.isModal = true
        popup.setAdapter(object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { FmIds.set(it, "fm-checkout-menu-item-$position") }
        })
        popup.setOnItemClickListener { _, _, position, _ ->
            instruction = items[position]
            view?.findViewById<TextView>(R.id.fm_checkout_instructions)?.text = instruction
            popup.dismiss()
        }
        popup.show()
    }

    fun quantity(i: Int) = quantities[i]

    fun title(i: Int) = itemTitles[i]

    fun setQuantity(i: Int, q: Int) {
        quantities[i] = q.coerceIn(0, 10)
        renderSummary()
    }

    private fun renderSummary() {
        val v = view ?: return
        val list = v.findViewById<LinearLayout>(R.id.fm_checkout_summary)
        list.removeAllViews()
        var total = 0L
        for (i in 0 until 3) {
            total += itemPrices[i] * quantities[i]
            list.addView(TextView(requireContext()).apply {
                text = "${quantities[i]} × ${itemTitles[i]}  ${money(itemPrices[i] * quantities[i])}"
                setPadding(0, context.dp(4), 0, context.dp(4))
            })
        }
        v.findViewById<TextView>(R.id.fm_checkout_total).text = "Total ${money(total)}"
    }

    private fun place(root: View) {
        fun text(id: Int) = root.findViewById<TextView>(id).text?.toString() ?: ""
        val form = linkedMapOf(
            "name" to text(R.id.fm_checkout_name), "email" to text(R.id.fm_checkout_email), "phone" to text(R.id.fm_checkout_phone),
            "address" to text(R.id.fm_checkout_address), "city" to text(R.id.fm_checkout_city),
            "postcode" to text(R.id.fm_checkout_postcode), "country" to text(R.id.fm_checkout_country), "date" to date,
            "card" to text(R.id.fm_checkout_card), "expiry" to text(R.id.fm_checkout_expiry), "cvv" to text(R.id.fm_checkout_cvv),
            "promo" to text(R.id.fm_checkout_promo), "instruction" to instruction, "rating" to rating.toString(),
            "agreed" to agreed.toString(), "notes" to text(R.id.fm_checkout_notes),
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val order = withContext(Dispatchers.IO) { runCatching { app.api.checkout(form) }.getOrNull() }
            root.findViewById<TextView>(R.id.fm_checkout_order).text = getString(R.string.fm_l_checkout_successDetail).replace("{order}", order ?: "?")
            root.findViewById<View>(R.id.fm_checkout_success).visibility = View.VISIBLE
        }
    }

    /** Promo suggestions from `/fx/api/promos?q=`, filtered off the main thread as the user types. */
    private inner class PromoAdapter : ArrayAdapter<Promo>(requireContext(), android.R.layout.simple_list_item_1) {
        private var items: List<Promo> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): Promo? = items.getOrNull(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? TextView) ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            v.text = items[position].label
            FmIds.set(v, "fm-checkout-promo-option-$position")
            return v
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val q = constraint?.toString()?.trim().orEmpty()
                val found = if (q.isEmpty()) emptyList() else runCatching { app.api.promos(q) }.getOrDefault(emptyList()).take(8)
                return FilterResults().apply {
                    values = found
                    count = found.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                items = (results?.values as? List<Promo>) ?: emptyList()
                if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence = (resultValue as? Promo)?.code ?: ""
        }
    }
}

/** "Edit items": a bottom sheet with a quantity stepper per item. */
class EditItemsSheet : com.google.android.material.bottomsheet.BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val owner = requireParentFragment() as CheckoutFragment
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(24))
        }
        root.addView(TextView(ctx).apply {
            text = getString(R.string.fm_l_checkout_edit)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        })
        for (i in 0 until 3) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val count = TextView(ctx).apply {
                text = owner.quantity(i).toString()
                minWidth = ctx.dp(32)
                gravity = android.view.Gravity.CENTER
            }
            row.addView(TextView(ctx).apply {
                text = owner.title(i)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.fm_ic_minus)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = "Less"
                layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
                setOnClickListener { owner.setQuantity(i, owner.quantity(i) - 1); count.text = owner.quantity(i).toString() }
            })
            row.addView(count)
            row.addView(ImageButton(ctx).apply {
                FmIds.set(this, "fm-checkout-edit-plus-$i")
                setImageResource(R.drawable.fm_ic_plus)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = "More"
                layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
                setOnClickListener { owner.setQuantity(i, owner.quantity(i) + 1); count.text = owner.quantity(i).toString() }
            })
            root.addView(row)
        }
        root.addView(MaterialButton(ctx).apply {
            FmIds.set(this, "fm-checkout-edit-done")
            text = getString(R.string.fm_l_checkout_done)
            setOnClickListener { dismiss() }
        })
        return root
    }
}
