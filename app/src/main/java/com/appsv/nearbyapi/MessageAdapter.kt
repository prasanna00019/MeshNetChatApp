package com.appsv.nearbyapi

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val messages: MutableList<Message>,
    private val currentUsername: String,
    private val onDeleteForMe: (String) -> Unit,
    private val onDeleteForEveryone: (String) -> Unit
) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageBubble: LinearLayout = view.findViewById(R.id.messageBubble)
        val messageTextView: TextView = view.findViewById(R.id.messageTextView)
        val senderTextView: TextView = view.findViewById(R.id.senderTextView)
        val timestampTextView: TextView = view.findViewById(R.id.timestampTextView)
        val messageImageView: ImageView = view.findViewById(R.id.messageImageView)
        val downloadImageButton: Button = view.findViewById(R.id.downloadImageButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context

        // Show timestamp
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.timestampTextView.text = sdf.format(Date(message.timestamp))
        holder.timestampTextView.visibility = View.VISIBLE

        // Handle deleted messages
        if (message.deletedForEveryone) {
            holder.messageTextView.visibility = View.VISIBLE
            holder.messageImageView.visibility = View.GONE
            holder.downloadImageButton.visibility = View.GONE
            holder.messageTextView.text = context.getString(R.string.message_deleted)
            holder.messageTextView.setTextColor(Color.GRAY)
            holder.messageTextView.setTypeface(null, android.graphics.Typeface.ITALIC)
        } else if (message.isDeleted) {
            holder.messageTextView.visibility = View.VISIBLE
            holder.messageImageView.visibility = View.GONE
            holder.downloadImageButton.visibility = View.GONE
            holder.messageTextView.text = context.getString(R.string.you_deleted_message)
            holder.messageTextView.setTextColor(Color.GRAY)
            holder.messageTextView.setTypeface(null, android.graphics.Typeface.ITALIC)
        } else {
            // Handle messageType
            if (message.messageType == "TEXT") {
                holder.messageTextView.visibility = View.VISIBLE
                holder.messageImageView.visibility = View.GONE
                holder.downloadImageButton.visibility = View.GONE
                holder.messageTextView.text = message.messageText
                holder.messageTextView.setTypeface(null, android.graphics.Typeface.NORMAL)
            } else if (message.messageType == "IMAGE") {
                try {
                    val imageBytes = Base64.decode(message.messageText, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    holder.messageImageView.setImageBitmap(bitmap)
                    holder.messageImageView.visibility = View.VISIBLE
                    holder.messageTextView.visibility = View.GONE

                    holder.downloadImageButton.visibility = View.VISIBLE
                    holder.downloadImageButton.setOnClickListener {
                        saveImageToGallery(holder.itemView.context, bitmap)
                    }
                } catch (e: Exception) {
                    Log.e("MessageAdapter", "Failed to decode Base64 image", e)
                    holder.messageImageView.visibility = View.GONE
                    holder.downloadImageButton.visibility = View.GONE
                    holder.messageTextView.visibility = View.VISIBLE
                    holder.messageTextView.text = context.getString(R.string.image_load_failed)
                }
            }
        }

        // Formatting (Sent vs Received)
        val params = holder.messageBubble.layoutParams as LinearLayout.LayoutParams

        if (message.senderId == currentUsername) {
            holder.senderTextView.visibility = View.GONE
            params.gravity = Gravity.END
            holder.messageBubble.setBackgroundResource(R.drawable.message_bubble_sent)
            holder.messageTextView.setTextColor(
                if (message.isDeleted || message.deletedForEveryone) Color.LTGRAY
                else ContextCompat.getColor(holder.itemView.context, android.R.color.white)
            )
            holder.timestampTextView.setTextColor(Color.WHITE)
        } else {
            holder.senderTextView.visibility = View.VISIBLE
            holder.senderTextView.text = if (message.senderId == currentUsername) context.getString(R.string.you) else message.senderId
            params.gravity = Gravity.START
            holder.messageBubble.setBackgroundResource(R.drawable.message_bubble_received)
            holder.messageTextView.setTextColor(
                if (message.isDeleted || message.deletedForEveryone) Color.LTGRAY
                else ContextCompat.getColor(holder.itemView.context, android.R.color.black)
            )
            holder.timestampTextView.setTextColor(Color.BLACK)
        }
        holder.messageBubble.layoutParams = params

        // Long click for delete options
        holder.messageBubble.setOnLongClickListener {
            if (message.messageType != "KEY" && message.messageType != "PRESENCE" && !message.deletedForEveryone) {
                showDeleteDialog(holder.itemView.context, message)
            }
            true
        }
    }

    private fun showDeleteDialog(context: Context, message: Message) {
        val options = if (message.senderId == currentUsername) {
            arrayOf(context.getString(R.string.delete_for_me), context.getString(R.string.delete_for_everyone), context.getString(R.string.cancel))
        } else {
            arrayOf(context.getString(R.string.delete_for_me), context.getString(R.string.cancel))
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.delete_message))
            .setItems(options) { dialog, which ->
                when (options[which]) {
                    context.getString(R.string.delete_for_me) -> {
                        onDeleteForMe(message.msgId)
                    }
                    context.getString(R.string.delete_for_everyone) -> {
                        onDeleteForEveryone(message.msgId)
                    }
                    context.getString(R.string.cancel) -> dialog.dismiss()
                }
            }
            .show()
    }

    override fun getItemCount() = messages.size

    fun updateMessage(msgId: String, isDeleted: Boolean, deletedForEveryone: Boolean) {
        val index = messages.indexOfFirst { it.msgId == msgId }
        if (index != -1) {
            messages[index] = messages[index].copy(
                isDeleted = isDeleted,
                deletedForEveryone = deletedForEveryone
            )
            notifyItemChanged(index)
        }
    }

    private fun saveImageToGallery(context: Context, bitmap: Bitmap) {
        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null

        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            fos = imageUri?.let { resolver.openOutputStream(it) }

            if (fos != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                Toast.makeText(context, context.getString(R.string.image_saved_to_gallery), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MessageAdapter", "Error saving image", e)
            Toast.makeText(context, context.getString(R.string.error_saving_image), Toast.LENGTH_SHORT).show()
        } finally {
            fos?.close()
        }
    }
}