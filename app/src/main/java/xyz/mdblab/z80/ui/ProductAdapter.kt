package xyz.mdblab.z80.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import xyz.mdblab.z80.R
import xyz.mdblab.z80.data.entities.Slot
import java.util.Locale

class ProductAdapter(
    private var slots: List<Slot>,
    private val onSlotClick: (Slot) -> Unit
) : RecyclerView.Adapter<ProductAdapter.ProductViewHolder>() {

    class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSlotCode: TextView = view.findViewById(R.id.tvSlotCode)
        val tvProductName: TextView = view.findViewById(R.id.tvProductName)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        val slot = slots[position]
        
        holder.tvSlotCode.text = slot.slotCode
        holder.tvProductName.text = slot.productName ?: "Unknown Product"
        holder.tvPrice.text = String.format(Locale.US, "$ %.2f", slot.price)
        
        holder.itemView.setOnClickListener {
            onSlotClick(slot)
        }
        
        // Visual feedback if disabled/empty stock could go here
        if (slot.stock <= 0) {
            holder.itemView.alpha = 0.5f // Dim items with no stock
        } else {
            holder.itemView.alpha = 1.0f
        }
    }

    override fun getItemCount() = slots.size

    fun updateList(newSlots: List<Slot>) {
        slots = newSlots
        notifyDataSetChanged()
    }
}
