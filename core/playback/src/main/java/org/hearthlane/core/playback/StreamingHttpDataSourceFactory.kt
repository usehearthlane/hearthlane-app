package org.hearthlane.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import org.hearthlane.core.connectivity.HttpStreamGetter

/**
 * [DataSource.Factory] that always creates [StreamingHttpDataSource] instances
 * bound to the given [HttpStreamGetter]. Stateless and safe to share across
 * requests; a fresh DataSource is created per playback. An optional
 * [TransferListener] (for example a bandwidth meter) is forwarded to every
 * created DataSource.
 */
@UnstableApi
class StreamingHttpDataSourceFactory(
    private val getter: HttpStreamGetter,
    private val connectTimeoutMs: Long,
    private val onBytes: (Long) -> Unit = {},
    private val onReadStall: () -> Unit = {},
    private val diagLabel: String = "EVENT",
    private val transferListener: TransferListener? = null,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        StreamingHttpDataSource(getter, connectTimeoutMs, onBytes, onReadStall, diagLabel).apply {
            transferListener?.let { addTransferListener(it) }
        }
}