package com.tracewayapp.fieldmart.feed

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.tracewayapp.fieldmart.FieldmartApplication
import com.tracewayapp.fieldmart.FmIds
import com.tracewayapp.fieldmart.compose.spark
import com.tracewayapp.fieldmart.data.FeedRow
import com.tracewayapp.fieldmart.data.i
import com.tracewayapp.fieldmart.data.s
import com.tracewayapp.fieldmart.dp
import com.tracewayapp.fieldmart.ui.R
import com.tracewayapp.fieldmart.widget.SparklineView

/**
 * The Views version of Feed (`fmToolkit=views`, hypothesis H-A2): a `RecyclerView` of the same 10,000 rows, the
 * sparklines as a custom `onDraw` view, the sticky week header as a real view over the list, pull to refresh.
 */
class FeedViewsFragment : Fragment() {
    private val app by lazy { FieldmartApplication.of(requireContext()) }
    private val model by lazy { FeedModel.of(app.api) }
    private val expanded = HashSet<Int>()
    private lateinit var list: RecyclerView
    private lateinit var layout: LinearLayoutManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
        }
        fun button(id: String, label: String, click: () -> Unit) = MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            FmIds.set(this, id)
            text = label
            setOnClickListener { click() }
        }
        val field = TextInputEditText(ctx).apply {
            FmIds.set(this, "fm-feed-goto-field")
            hint = getString(R.string.fm_l_feed_goTo)
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(ctx.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val refresh = SwipeRefreshLayout(ctx)
        val title = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(4), 0)
            addView(TextView(ctx).apply {
                text = getString(R.string.fm_l_feed_title)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("fm-feed-refresh", getString(R.string.fm_l_feed_refresh)) { doRefresh(refresh) })
        }
        root.addView(title)
        controls.addView(button("fm-feed-jump-start", getString(R.string.fm_l_feed_jumpStart)) { layout.scrollToPositionWithOffset(0, 0) })
        controls.addView(button("fm-feed-jump-end", getString(R.string.fm_l_feed_jumpEnd)) { layout.scrollToPositionWithOffset(model.total - 1, 0) })
        controls.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(field)
        controls.addView(button("fm-feed-goto-go", getString(R.string.fm_l_feed_go)) {
            field.text?.toString()?.toIntOrNull()?.let { layout.scrollToPositionWithOffset(it.coerceIn(0, model.total - 1), 0) }
        })
        root.addView(controls)

        val frame = FrameLayout(ctx)
        list = RecyclerView(ctx).apply { FmIds.set(this, "fm-feed-list") }
        layout = LinearLayoutManager(ctx)
        list.layoutManager = layout
        list.adapter = Rows()
        refresh.addView(list)
        refresh.setOnRefreshListener { doRefresh(refresh) }
        frame.addView(refresh)
        val sticky = TextView(ctx).apply {
            setBackgroundColor(0xFFF3F4F6.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(6))
            text = getString(R.string.fm_l_feed_header).replace("{n}", "1")
        }
        frame.addView(sticky, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
                val header = first / FeedModel.SECTION * FeedModel.SECTION
                val week = model.row(header)?.text ?: getString(R.string.fm_l_feed_header).replace("{n}", "${first / FeedModel.SECTION + 1}")
                if (sticky.text != week) sticky.text = week
            }
        })
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        model.listener = { page ->
            list.post { list.adapter?.notifyItemRangeChanged(page * FeedModel.PAGE, FeedModel.PAGE) }
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        model.listener = null
    }

    private fun doRefresh(refresh: SwipeRefreshLayout) {
        refresh.isRefreshing = true
        model.refresh()
        refresh.postDelayed({ refresh.isRefreshing = false }, 600)
    }

    private inner class Rows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemCount() = model.total
        override fun getItemId(position: Int) = position.toLong()

        override fun getItemViewType(position: Int): Int = when (model.row(position)?.type) {
            null -> 0
            "shipment" -> 2
            "alert" -> 3
            "message" -> 4
            "header" -> 5
            else -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minimumHeight = ctx.dp(56)
            }
            when (viewType) {
                2 -> {
                    row.addView(ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundResource(R.color.fm_placeholder)
                    }, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)))
                    row.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ctx.dp(12), 0, 0, 0)
                        addView(TextView(ctx).apply { maxLines = 1; setTypeface(typeface, Typeface.BOLD) })
                        addView(TextView(ctx).apply { maxLines = 1 })
                        addView(LinearProgressIndicator(ctx).apply { max = 100 })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                3 -> {
                    row.addView(TextView(ctx).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(ctx.dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
                    row.addView(TextView(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(SparklineView(ctx), LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(24)))
                }
                4 -> {
                    row.gravity = Gravity.TOP
                    row.addView(ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundResource(R.color.fm_placeholder)
                    }, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
                    row.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ctx.dp(12), 0, 0, 0)
                        addView(TextView(ctx).apply { setTypeface(typeface, Typeface.BOLD) })
                        addView(TextView(ctx))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                1 -> {
                    row.addView(ImageView(ctx).apply { setImageResource(R.drawable.fm_ic_order) }, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
                    row.addView(TextView(ctx).apply { maxLines = 1; setPadding(ctx.dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(TextView(ctx).apply { textSize = 12f; setTextColor(0xFF6B7280.toInt()) })
                }
                5 -> {
                    row.setBackgroundColor(0xFFF3F4F6.toInt())
                    row.minimumHeight = 0
                    row.setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(6))
                    row.addView(TextView(ctx).apply { setTypeface(typeface, Typeface.BOLD) })
                }
                else -> row.setBackgroundColor(0xFFF1F3F5.toInt())
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView as LinearLayout
            FmIds.set(v, "fm-feed-row-$position")
            val row: FeedRow = model.row(position) ?: return
            when (holder.itemViewType) {
                2 -> {
                    (v.getChildAt(0) as ImageView).load(app.api.productImage(row.o.i("productId") ?: position, 0, 160))
                    val col = v.getChildAt(1) as LinearLayout
                    (col.getChildAt(0) as TextView).text = row.text
                    (col.getChildAt(1) as TextView).text = row.o.s("detail") ?: ""
                    (col.getChildAt(2) as LinearProgressIndicator).progress = row.o.i("progress") ?: 0
                }
                3 -> {
                    (v.getChildAt(0) as TextView).text = row.o.s("symbol") ?: ""
                    (v.getChildAt(1) as TextView).text = row.text
                    val spark = row.spark()
                    (v.getChildAt(2) as SparklineView).setValues(spark, spark.isEmpty() || spark.last() >= spark.first())
                }
                4 -> {
                    (v.getChildAt(0) as ImageView).load(app.api.avatarImage(row.o.i("avatarId", "avatar") ?: (position % 64 + 1)))
                    val col = v.getChildAt(1) as LinearLayout
                    (col.getChildAt(0) as TextView).text = row.text
                    val body = col.getChildAt(1) as TextView
                    body.text = row.lines.joinToString("\n")
                    body.maxLines = if (position in expanded) Int.MAX_VALUE else 1
                    v.setOnClickListener {
                        if (!expanded.add(position)) expanded.remove(position)
                        notifyItemChanged(position)
                    }
                }
                1 -> {
                    (v.getChildAt(1) as TextView).text = row.text
                    (v.getChildAt(2) as TextView).text = row.o.s("time") ?: ""
                }
                5 -> (v.getChildAt(0) as TextView).text = row.text
            }
        }
    }
}
