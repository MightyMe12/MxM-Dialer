package com.secretdialer.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretdialer.app.ContactInfo
import com.secretdialer.app.R

class ContactListAdapter(
    private val onClick: (ContactInfo) -> Unit
) : ListAdapter<ContactInfo, ContactListAdapter.VH>(DIFF) {

    var searchQuery: String = ""

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), searchQuery, onClick)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.tvName)
        private val sub: TextView = itemView.findViewById(R.id.tvSub)
        private val avatarContainer: View = itemView.findViewById(R.id.avatarContainer)
        private val tvAvatarInitials: TextView = itemView.findViewById(R.id.tvAvatarInitials)
        private val ivAvatarPhoto: android.widget.ImageView = itemView.findViewById(R.id.ivAvatarPhoto)

        fun bind(item: ContactInfo, query: String, onClick: (ContactInfo) -> Unit) {
            val highlighted = com.secretdialer.app.DialerHelper.highlightContact(
                item.name,
                item.number,
                query,
                0xFF30D158.toInt()
            )
            name.text = highlighted.first
            sub.text = highlighted.second
            itemView.setOnClickListener { onClick(item) }

            val initials = com.secretdialer.app.DialerHelper.getInitials(item.name)
            tvAvatarInitials.text = initials
            val color = com.secretdialer.app.DialerHelper.getAvatarColor(item.name)
            avatarContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(color)

            if (item.photoUri != null) {
                val bitmap = com.secretdialer.app.DialerHelper.loadContactPhoto(itemView.context, item.photoUri)
                if (bitmap != null) {
                    ivAvatarPhoto.visibility = View.VISIBLE
                    ivAvatarPhoto.setImageBitmap(bitmap)
                    tvAvatarInitials.visibility = View.GONE
                } else {
                    ivAvatarPhoto.visibility = View.GONE
                    tvAvatarInitials.visibility = View.VISIBLE
                }
            } else {
                ivAvatarPhoto.visibility = View.GONE
                tvAvatarInitials.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ContactInfo>() {
            override fun areItemsTheSame(old: ContactInfo, new: ContactInfo): Boolean =
                old.id == new.id && old.number == new.number

            override fun areContentsTheSame(old: ContactInfo, new: ContactInfo): Boolean =
                old == new
        }
    }
}
