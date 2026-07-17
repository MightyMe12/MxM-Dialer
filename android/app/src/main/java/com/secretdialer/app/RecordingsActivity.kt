package com.secretdialer.app

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.secretdialer.app.databinding.ActivityRecordingsBinding
import com.secretdialer.app.recorder.CallRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var adapter: RecordingAdapter
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RecordingAdapter(
            onPlay = { file -> play(file) },
            onShare = { file -> share(file) },
            onDelete = { file -> confirmDelete(file) },
            onLongClick = { file -> openWithExternalPlayer(file) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.itemAnimator = null
        binding.recycler.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { finish() }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val files = CallRecorder.listRecordings(this)
        adapter.submit(files)
        binding.emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun play(file: File) {
        stopPlayer()
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            player = MediaPlayer().apply {
                // Use FileProvider URI + grant flag so Android 7+ media stack can read the private file
                setDataSource(this@RecordingsActivity, uri)
                prepare()
                start()
                setOnCompletionListener { stopPlayer() }
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Cannot play: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWithExternalPlayer(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/mpeg")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Open recording with…"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "No audio player found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayer() {
        player?.release()
        player = null
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_recording_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                CallRecorder.deleteRecording(file)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun share(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Call Recording"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Could not share recording", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }
}

class RecordingAdapter(
    private val onPlay: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit,
    private val onLongClick: (File) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.VH>() {

    private val items = mutableListOf<File>()
    private val fmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())

    fun submit(files: List<File>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], fmt, onPlay, onShare, onDelete, onLongClick)
    }

    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvTitle)
        private val sub: TextView = itemView.findViewById(R.id.tvSub)
        private val btnShare: View = itemView.findViewById(R.id.btnShare)

        fun bind(file: File, fmt: SimpleDateFormat, onPlay: (File) -> Unit, onShare: (File) -> Unit, onDelete: (File) -> Unit, onLongClick: (File) -> Unit) {
            title.text = file.nameWithoutExtension
            sub.text = fmt.format(Date(file.lastModified()))
            itemView.setOnClickListener { onPlay(file) }
            itemView.setOnLongClickListener {
                onLongClick(file)
                true
            }
            btnShare.setOnClickListener { onShare(file) }
            itemView.findViewById<View>(R.id.btnDelete).setOnClickListener { onDelete(file) }
        }
    }
}
