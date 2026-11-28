package com.tracewayapp.fieldmart.product

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.tracewayapp.fieldmart.Cart
import com.tracewayapp.fieldmart.FieldmartApplication
import com.tracewayapp.fieldmart.FmIds
import com.tracewayapp.fieldmart.MainActivity
import com.tracewayapp.fieldmart.data.ProductDetail
import com.tracewayapp.fieldmart.data.Review
import com.tracewayapp.fieldmart.dp
import com.tracewayapp.fieldmart.money
import com.tracewayapp.fieldmart.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Product (02-APP-SPEC.md §2.2), Views: one `RecyclerView` (`fm-product-reviews`) whose first item is the product
 * (a `ViewPager2` gallery with dots and a counter, size chips, colour swatches, a quantity stepper, a description
 * with "Read more") and whose other items are the reviews, fetched 50 at a time. A single recycled list keeps the 500
 * reviews virtualized, which a list nested in a scroll view would not. "Add to cart" sits in a sticky bar.
 */
class ProductFragment : Fragment() {
    val productId: Int get() = requireArguments().getInt("id")
    private val app by lazy { FieldmartApplication.of(requireContext()) }
    private var detail: ProductDetail? = null
    private val reviews = ArrayList<Review>()
    private val helpful = HashSet<String>()
    private var nextPage = FIRST_PAGE
    private var loading = false
    private var exhausted = false
    private var quantity = 1
    private var size = -1
    private var colour = -1
    private var expanded = false
    private val header = HeaderAdapter()
    private val list = ReviewAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fm_product, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.fm_nav_back).setOnClickListener { (requireActivity() as MainActivity).back() }
        val rv = view.findViewById<RecyclerView>(R.id.fm_product_reviews)
        val layout = LinearLayoutManager(requireContext())
        rv.layoutManager = layout
        rv.adapter = ConcatAdapter(header, list)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (layout.findLastVisibleItemPosition() > 1 + reviews.size - 10) loadReviews()
            }
        })
        view.findViewById<MaterialButton>(R.id.fm_product_add).setOnClickListener {
            Cart.add(quantity)
            Snackbar.make(view, R.string.fm_l_product_added, 3000).setAnchorView(it).show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { app.api.product(productId) }.getOrNull() } ?: return@launch
            detail = d
            view.findViewById<TextView>(R.id.fm_product_bar_title).text = d.product.title
            view.findViewById<TextView>(R.id.fm_product_bar_price).text = money(d.product.priceCents)
            header.notifyItemChanged(0)
        }
        loadReviews()
    }

    private fun loadReviews() {
        if (loading || exhausted) return
        loading = true
        val page = nextPage
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.api.reviews(productId, page) }.getOrNull() }
            loading = false
            if (result == null) return@launch
            if (result.items.isEmpty()) exhausted = true
            nextPage = page + 1
            val start = reviews.size
            reviews.addAll(result.items)
            list.notifyItemRangeInserted(start, result.items.size)
            header.notifyItemChanged(0, "count")
        }
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.fm_product_header, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val d = detail
            v.findViewById<TextView>(R.id.fm_product_reviews_title).text =
                getString(R.string.fm_l_product_reviews)
            if (d == null) return
            val p = d.product
            v.findViewById<TextView>(R.id.fm_product_title).text = p.title
            v.findViewById<TextView>(R.id.fm_product_brand).text = p.brand
            v.findViewById<TextView>(R.id.fm_product_price).text = money(p.priceCents)
            v.findViewById<TextView>(R.id.fm_product_rating).text =
                "★ %.1f ".format(java.util.Locale.US, p.rating) + getString(R.string.fm_l_home_reviews).replace("{n}", p.reviewCount.toString())
            v.findViewById<TextView>(R.id.fm_product_stock).text = getString(R.string.fm_l_product_stock).replace("{n}", d.stock.toString())
            bindGallery(v, p.imageCount.coerceIn(1, 8))
            bindSizes(v.findViewById(R.id.fm_product_sizes), d.sizes)
            bindColours(v.findViewById(R.id.fm_product_colours), d)
            val qty = v.findViewById<TextView>(R.id.fm_product_qty)
            val qtyText = getString(R.string.fm_l_product_quantity)
            qty.text = qtyText.replace("{n}", quantity.toString())
            v.findViewById<View>(R.id.fm_product_qty_minus).setOnClickListener { quantity = (quantity - 1).coerceAtLeast(1); qty.text = qtyText.replace("{n}", quantity.toString()) }
            v.findViewById<View>(R.id.fm_product_qty_plus).setOnClickListener { quantity = (quantity + 1).coerceAtMost(10); qty.text = qtyText.replace("{n}", quantity.toString()) }
            val description = v.findViewById<TextView>(R.id.fm_product_description)
            description.text = d.description
            description.maxLines = if (expanded) Int.MAX_VALUE else 3
            val more = v.findViewById<MaterialButton>(R.id.fm_product_readmore)
            more.setText(if (expanded) R.string.fm_l_product_readLess else R.string.fm_l_product_readMore)
            more.setOnClickListener {
                expanded = !expanded
                description.maxLines = if (expanded) Int.MAX_VALUE else 3
                more.setText(if (expanded) R.string.fm_l_product_readLess else R.string.fm_l_product_readMore)
            }
        }

        private fun bindGallery(v: View, count: Int) {
            val pager = v.findViewById<ViewPager2>(R.id.fm_product_gallery)
            if (pager.adapter != null) return
            val counter = v.findViewById<TextView>(R.id.fm_product_counter)
            val dots = v.findViewById<LinearLayout>(R.id.fm_product_dots)
            for (i in 0 until count) dots.addView(View(v.context).apply {
                setBackgroundResource(R.drawable.fm_dot)
                layoutParams = LinearLayout.LayoutParams(context.dp(7), context.dp(7)).apply { marginEnd = context.dp(5) }
            })
            pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = count
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                    object : RecyclerView.ViewHolder(ImageView(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundResource(R.color.fm_placeholder)
                    }) {}

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as ImageView).load(app.api.productImage(productId, position, 800))
                }
            }
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    counter.text = getString(R.string.fm_l_product_galleryCounter).replace("{i}", "${position + 1}").replace("{total}", "$count")
                    for (i in 0 until dots.childCount) dots.getChildAt(i).alpha = if (i == position) 1f else 0.45f
                }
            })
        }

        private fun bindSizes(group: ChipGroup, sizes: List<String>) {
            if (group.childCount > 0) return
            sizes.take(6).forEachIndexed { i, s ->
                group.addView(Chip(group.context).apply {
                    FmIds.set(this, "fm-product-size-$i")
                    text = s
                    isCheckable = true
                    isChecked = i == size
                    setOnClickListener { size = i }
                })
            }
        }

        private fun bindColours(row: LinearLayout, d: ProductDetail) {
            if (row.childCount > 0) return
            d.colours.take(8).forEachIndexed { i, c ->
                row.addView(View(row.context).apply {
                    FmIds.set(this, "fm-product-colour-$i")
                    contentDescription = c.name
                    background = swatch(c.argb ?: SWATCHES[i % SWATCHES.size], i == colour)
                    layoutParams = LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply { marginEnd = context.dp(10) }
                    setOnClickListener {
                        colour = i
                        for (j in 0 until row.childCount) {
                            val cc = d.colours[j]
                            row.getChildAt(j).background = swatch(cc.argb ?: SWATCHES[j % SWATCHES.size], j == i)
                        }
                    }
                })
            }
        }

        private fun swatch(argb: Int, selected: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(argb)
            setStroke(requireContext().dp(if (selected) 3 else 1), if (selected) 0xFF0B6E4F.toInt() else 0x33000000)
        }
    }

    /** A review row; its views are kept here because the Helpful button's id is the element id of its review. */
    private class ReviewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.fm_review_avatar)
        val author: TextView = v.findViewById(R.id.fm_review_author)
        val date: TextView = v.findViewById(R.id.fm_review_date)
        val stars: TextView = v.findViewById(R.id.fm_review_stars)
        val text: TextView = v.findViewById(R.id.fm_review_text)
        val helpful: MaterialButton = v.findViewById(R.id.fm_review_helpful)
    }

    private inner class ReviewAdapter : RecyclerView.Adapter<ReviewHolder>() {
        override fun getItemCount() = reviews.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ReviewHolder(LayoutInflater.from(parent.context).inflate(R.layout.fm_product_review, parent, false))

        override fun onBindViewHolder(holder: ReviewHolder, position: Int) {
            val r = reviews[position]
            holder.avatar.load(app.api.avatarImage(r.avatarId))
            holder.author.text = r.author
            holder.date.text = r.date
            holder.stars.text = "★".repeat(r.rating.coerceIn(0, 5)) + "☆".repeat(5 - r.rating.coerceIn(0, 5))
            holder.text.text = r.text
            FmIds.set(holder.helpful, "fm-product-helpful-${r.id}")
            holder.helpful.text = getString(R.string.fm_l_product_helpful) + if (r.id in helpful) " (1)" else ""
            holder.helpful.setOnClickListener {
                if (!helpful.add(r.id)) helpful.remove(r.id)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    companion object {
        const val FIRST_PAGE = 0
        private val SWATCHES = intArrayOf(
            0xFF1F2937.toInt(), 0xFFF9FAFB.toInt(), 0xFFB91C1C.toInt(), 0xFF1D4ED8.toInt(),
            0xFF047857.toInt(), 0xFFF59E0B.toInt(), 0xFF7C3AED.toInt(), 0xFF9CA3AF.toInt(),
        )

        fun of(id: Int) = ProductFragment().apply { arguments = Bundle().apply { putInt("id", id) } }
    }
}
