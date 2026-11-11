package com.homeprojector.launcher.nas

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.homeprojector.launcher.Prefs
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlin.math.min

/**
 * Потоковое чтение видео с NAS по SMB для ExoPlayer.
 * Поддерживает перемотку (seek) через SmbRandomAccessFile.
 */
class SmbDataSource(private val prefs: Prefs) : BaseDataSource(true) {

    private var file: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val smbFile = SmbFile(dataSpec.uri.toString(), NasClient(prefs).newContext())
        val raf = SmbRandomAccessFile(smbFile, "r")
        if (dataSpec.position > 0) {
            raf.seek(dataSpec.position)
        }
        file = raf
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> smbFile.length() - dataSpec.position
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), bytesRemaining).toInt()
        }
        val read = file?.read(buffer, offset, toRead) ?: return C.RESULT_END_OF_INPUT
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** smb:// — через SmbDataSource, остальное (file/content/http) — стандартный источник. */
class RoutingDataSource(
    private val fallback: DataSource,
    private val prefs: Prefs
) : DataSource {

    private var active: DataSource? = null

    private fun pick(uri: Uri): DataSource =
        if (uri.scheme == "smb") SmbDataSource(prefs) else fallback

    override fun open(dataSpec: DataSpec): Long {
        val ds = pick(dataSpec.uri)
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        active?.close()
        active = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
    }
}

class ProjectorDataSourceFactory(
    private val context: android.content.Context,
    private val prefs: Prefs
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        RoutingDataSource(DefaultDataSource.Factory(context).createDataSource(), prefs)
}