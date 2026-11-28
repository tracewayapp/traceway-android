package com.tracewayapp.fieldmart.home

import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tracewayapp.fieldmart.Cart
import com.tracewayapp.fieldmart.FieldmartApplication
import com.tracewayapp.fieldmart.FmIds
import com.tracewayapp.fieldmart.MainActivity
import com.tracewayapp.fieldmart.Markers
import com.tracewayapp.fieldmart.data.Api
import com.tracewayapp.fieldmart.data.Category
import com.tracewayapp.fieldmart.data.Deal
import com.tracewayapp.fieldmart.data.Hero
import com.tracewayapp.fieldmart.data.Product
import com.tracewayapp.fieldmart.dp
import com.tracewayapp.fieldmart.money
import com.tracewayapp.fieldmart.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home (02-APP-SPEC.md §2.1), Views: one `RecyclerView` with a `GridLayoutManager` holding the hero carousel
 * (`ViewPager2`, advancing every 4 s), the category chips, the deals carousel (a nested horizontal list) and the
 * 2,000-product grid, loaded 40 at a time as the user nears the end. Pictures load with Coil's defaults.
 */
class HomeFragment : Fragment(), MainActivity.Scrollable {
    private val app by lazy { FieldmartApplication.of(requireContext()) }
    private lateinit var grid: RecyclerView
    private val adapter = HomeAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var hero: ViewPager2? = null
    private var category: Int? = null
    private var nextPage = FIRST_PAGE
    private var loading = false
    private var exhausted = false
    private var generation = 0
    private val fullyLoaded = HashSet<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fm_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        grid = view.findViewById(R.id.fm_home_grid)
        val layout = GridLayoutManager(requireContext(), 2)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == PRODUCT) 1 else 2
        }
        grid.layoutManager = layout
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (layout.findLastVisibleItemPosition() > adapter.itemCount - 12) loadMore()
            }
        })
        view.findViewById<View>(R.id.fm_home_logo).setOnClickListener { scrollToTop() }
        view.findViewById<View>(R.id.fm_home_search).setOnClickListener {
            (requireActivity() as MainActivity).push(MainActivity.Screen("search"))
        }
        view.findViewById<View>(R.id.fm_home_cart).setOnClickListener {
            (requireActivity() as MainActivity).select(MainActivity.Tab.CHECKOUT)
        }
        val badge = view.findViewById<TextView>(R.id.fm_cart_badge)
        badge.text = Cart.count.toString()
        Cart.listen { badge.text = Cart.count.toString() }

        viewLifecycleOwner.lifecycleScope.launch {
            val api = app.api
            val cats = withContext(Dispatchers.IO) { runCatching { api.categories() }.getOrDefault(emptyList()) }
            adapter.categories = cats
            adapter.notifyItemChanged(1)
            val slides = withContext(Dispatchers.IO) { runCatching { api.hero() }.getOrDefault(emptyList()) }
            if (slides.isNotEmpty()) {
                adapter.slides = slides
                hero?.adapter?.notifyDataSetChanged()
            }
            val deals = withContext(Dispatchers.IO) {
                val list = runCatching { api.deals() }.getOrDefault(emptyList()).take(20)
                list.map { d -> async { d to runCatching { api.product(d.productId).product }.getOrNull() } }.awaitAll()
            }
            adapter.deals = deals.mapNotNull { (d, p) -> p?.let { d to it } }
            adapter.notifyItemChanged(2)
        }
        loadMore()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(advance, HERO_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(advance)
    }

    private val advance = object : Runnable {
        override fun run() {
            hero?.let { it.currentItem = (it.currentItem + 1) % HERO_SLIDES }
            handler.postDelayed(this, HERO_MS)
        }
    }

    override fun scrollToTop() {
        grid.scrollToPosition(0)
    }

    /** A chip replaces the grid's content; the screen scrolls so the chips and the category's first products show. */
    private fun selectCategory(id: Int?) {
        if (id == category) return
        category = id
        generation++
        nextPage = FIRST_PAGE
        exhausted = false
        loading = false
        adapter.replaceProducts(emptyList())
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(1, 0)
        loadMore()
    }

    private fun loadMore() {
        if (loading || exhausted) return
        loading = true
        val gen = generation
        val page = nextPage
        val cat = category
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.api.products(page, Api.PAGE_SIZE, cat) }.getOrNull() }
            if (gen != generation) return@launch
            loading = false
            if (result == null) return@launch
            if (result.items.isEmpty()) exhausted = true
            nextPage = page + 1
            adapter.appendProducts(result.items)
        }
    }

    /** Home is fully drawn once the first hero slide and the first eight grid pictures are decoded. */
    private fun loaded(key: String) {
        if (fullyLoaded.size >= 9 || !fullyLoaded.add(key) || fullyLoaded.size < 9) return
        if (Markers.fully()) {
            activity?.reportFullyDrawn()
            app.telemetry.reportFullyDisplayed()
        }
    }

    private inner class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var categories: List<Category> = emptyList()
        var slides: List<Hero> = emptyList()
        var deals: List<Pair<Deal, Product>> = emptyList()
        private val products = ArrayList<Product>()

        init {
            setHasStableIds(true)
        }

        fun replaceProducts(items: List<Product>) {
            products.clear()
            products.addAll(items)
            notifyDataSetChanged()
        }

        fun appendProducts(items: List<Product>) {
            val start = HEADERS + products.size
            products.addAll(items)
            notifyItemRangeInserted(start, items.size)
        }

        override fun getItemCount() = HEADERS + products.size

        override fun getItemViewType(position: Int) = when (position) {
            0 -> HERO
            1 -> CHIPS
            2 -> DEALS
            3 -> TITLE
            else -> PRODUCT
        }

        override fun getItemId(position: Int): Long =
            if (position < HEADERS) -1L - position else products[position - HEADERS].id.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = when (viewType) {
                HERO -> inflater.inflate(R.layout.fm_home_hero, parent, false)
                CHIPS -> HorizontalScrollView(parent.context).apply {
                    id = R.id.fm_home_chips
                    isHorizontalScrollBarEnabled = false
                    addView(ChipGroup(parent.context).apply {
                        isSingleSelection = true
                        isSingleLine = true
                        setPadding(context.dp(6), context.dp(4), context.dp(6), 0)
                    })
                }
                DEALS -> LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title(parent, getString(R.string.fm_l_home_deals)))
                    addView(RecyclerView(parent.context).apply {
                        id = R.id.fm_home_deals
                        layoutManager = LinearLayoutManager(parent.context, RecyclerView.HORIZONTAL, false)
                        adapter = DealAdapter()
                    })
                }
                TITLE -> title(parent, getString(R.string.fm_l_home_grid))
                else -> inflater.inflate(R.layout.fm_home_product, parent, false)
            }
            if (view.layoutParams == null) {
                view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        private fun title(parent: ViewGroup, text: String) = TextView(parent.context).apply {
            this.text = text
            setTextAppearance(R.style.Fieldmart_Title)
            setPadding(context.dp(10), context.dp(12), context.dp(10), context.dp(4))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (getItemViewType(position)) {
                HERO -> bindHero(v)
                CHIPS -> bindChips(((v as HorizontalScrollView).getChildAt(0)) as ChipGroup)
                DEALS -> (v.findViewById<RecyclerView>(R.id.fm_home_deals).adapter as DealAdapter).notifyDataSetChanged()
                PRODUCT -> bindProduct(v, products[position - HEADERS], position - HEADERS)
            }
        }

        private fun bindHero(v: View) {
            val pager = v.findViewById<ViewPager2>(R.id.fm_home_hero)
            if (pager.adapter != null) return
            hero = pager
            pager.adapter = HeroAdapter()
            val dots = v.findViewById<LinearLayout>(R.id.fm_hero_dots)
            for (i in 0 until HERO_SLIDES) dots.addView(View(v.context).apply {
                setBackgroundResource(R.drawable.fm_dot)
                layoutParams = LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginEnd = context.dp(6) }
            })
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    for (i in 0 until dots.childCount) dots.getChildAt(i).alpha = if (i == position) 1f else 0.45f
                }
            })
        }

        private fun bindChips(group: ChipGroup) {
            if (group.childCount == categories.size && categories.isNotEmpty()) return
            group.removeAllViews()
            categories.take(12).forEachIndexed { i, c ->
                group.addView(Chip(group.context).apply {
                    FmIds.set(this, "fm-home-chip-$i")
                    text = c.name
                    isCheckable = true
                    isChecked = category == c.id
                    setOnClickListener { selectCategory(if (isChecked) c.id else null) }
                })
            }
        }

        private fun bindProduct(v: View, p: Product, index: Int) {
            FmIds.set(v, "fm-home-product-${p.id}")
            v.setOnClickListener { (requireActivity() as MainActivity).push(MainActivity.Screen("product", p.id)) }
            v.findViewById<TextView>(R.id.fm_cell_title).text = p.title
            v.findViewById<TextView>(R.id.fm_cell_price).text = money(p.priceCents)
            v.findViewById<TextView>(R.id.fm_cell_compare).apply {
                val compare = p.compareAtCents?.takeIf { it > p.priceCents }
                text = compare?.let { money(it) } ?: ""
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            v.findViewById<TextView>(R.id.fm_cell_rating).text =
                "★ %.1f ".format(java.util.Locale.US, p.rating) + getString(R.string.fm_l_home_reviews).replace("{n}", p.reviewCount.toString())
            val badge = v.findViewById<TextView>(R.id.fm_cell_badge)
            val name = p.badges.firstOrNull { it.equals("new", true) || it.equals("sale", true) }
            badge.visibility = if (name != null) View.VISIBLE else View.GONE
            badge.text = when (name?.lowercase()) {
                "new" -> getString(R.string.fm_l_home_new)
                "sale" -> getString(R.string.fm_l_home_sale)
                else -> ""
            }
            v.findViewById<ImageView>(R.id.fm_cell_image).load(app.api.productImage(p.id, 0, 320)) {
                if (index < 8 && category == null) listener(onSuccess = { _, _ -> loaded("p$index") })
            }
        }
    }

    private inner class HeroAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = HERO_SLIDES

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.fm_hero_slide, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val slide = adapter.slides.getOrNull(position)
            v.findViewById<TextView>(R.id.fm_hero_title).text = slide?.title ?: ""
            v.findViewById<TextView>(R.id.fm_hero_subtitle).text = slide?.subtitle ?: ""
            v.findViewById<ImageView>(R.id.fm_hero_image).load(slide?.let { app.api.fixture(it.image) } ?: app.api.heroImage(position + 1)) {
                if (position == 0) listener(onSuccess = { _, _ -> loaded("hero") })
            }
        }
    }

    private inner class DealAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = adapter.deals.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.fm_home_deal, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val (deal, p) = adapter.deals[position]
            val v = holder.itemView
            FmIds.set(v, "fm-home-deal-$position")
            v.setOnClickListener { (requireActivity() as MainActivity).push(MainActivity.Screen("product", p.id)) }
            v.findViewById<TextView>(R.id.fm_deal_title).text = p.title
            v.findViewById<TextView>(R.id.fm_deal_price).text = money(p.priceCents)
            v.findViewById<TextView>(R.id.fm_deal_compare).apply {
                text = money(p.compareAtCents ?: (p.priceCents * 100 / (100 - deal.percent.coerceIn(0, 90))))
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            v.findViewById<TextView>(R.id.fm_deal_percent).text = "-${deal.percent}%"
            v.findViewById<ImageView>(R.id.fm_deal_badge).load(app.api.badgeImage(deal.badge))
            v.findViewById<ImageView>(R.id.fm_deal_image).load(app.api.productImage(p.id, 0, 320))
        }
    }

    companion object {
        const val HERO_MS = 4000L
        const val HERO_SLIDES = 8
        const val FIRST_PAGE = 0
        private const val HEADERS = 4
        private const val HERO = 0
        private const val CHIPS = 1
        private const val DEALS = 2
        private const val TITLE = 3
        private const val PRODUCT = 4

    }
}
