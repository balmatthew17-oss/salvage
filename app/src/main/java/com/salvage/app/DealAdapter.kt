package com.salvage.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DealAdapter(
    private val context: Context,
    private val onOpen: (Deal) -> Unit,
    private val onHide: (Deal) -> Unit,
) : RecyclerView.Adapter<DealAdapter.Holder>() {

    private var items: List<Deal> = emptyList()

    fun submit(list: List<Deal>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(context).inflate(R.layout.item_deal, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val d = items[position]
        val now = System.currentTimeMillis()

        h.title.text = d.title
        h.price.text = money(d.total)
        val post = d.postage
        h.postage.text = when {
            post == null -> "${money(d.price)} · postage not listed"
            post == 0.0 -> "${money(d.price)} · free postage"
            else -> "${money(d.price)} + ${money(post)} post"
        }

        val kind = if (d.auction) {
            val bids = if (d.bids == 1) "1 bid" else "${d.bids} bids"
            val left = d.endTime?.let { if (it > now) " · ${duration(it - now)} left" else " · ended" } ?: ""
            "Auction · $bids$left"
        } else {
            "Buy it now" + if (d.bestOffer) " · offers" else ""
        }
        h.kind.text = kind
        h.kind.setTextColor(context.getColor(if (d.auction) R.color.auction else R.color.muted))

        val meta = ArrayList<String>()
        meta.add(d.console.label)
        d.location?.let { meta.add(it) }
        d.created?.let { meta.add("listed ${duration(now - it)} ago") }
        h.meta.text = meta.joinToString(" · ")

        h.tags.removeAllViews()
        val tags = d.analysis.tags
        tags.take(4).forEach { h.tags.addView(pill(it.label, it.level)) }
        if (tags.size > 4) h.tags.addView(pill("+${tags.size - 4}", null))
        if (tags.isEmpty()) h.tags.addView(pill("Fault not stated", null))

        ImageLoader.load(d.image, h.image)

        h.itemView.setOnClickListener { onOpen(d) }
        h.itemView.setOnLongClickListener { onHide(d); true }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun pill(text: String, level: Level?): TextView {
        val color = when (level) {
            Level.EASY -> context.getColor(R.color.easy)
            Level.MAYBE -> context.getColor(R.color.maybe)
            Level.RISKY -> context.getColor(R.color.risky)
            null -> context.getColor(R.color.muted)
        }
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(color)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val title: TextView = v.findViewById(R.id.title)
        val price: TextView = v.findViewById(R.id.price)
        val postage: TextView = v.findViewById(R.id.postage)
        val kind: TextView = v.findViewById(R.id.kind)
        val meta: TextView = v.findViewById(R.id.meta)
        val tags: LinearLayout = v.findViewById(R.id.tags)
    }
}
