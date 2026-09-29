package org.hearthlane.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import org.hearthlane.core.connectivity.HttpBytesGetter

/**
 * [DataSource.Factory] that always creates [HttpBytesDataSource] instances
 * bound to the given getter. Stateless and safe to share across requests.
 */
@UnstableApi
class HttpBytesDataSourceFactory(
    private val getter: HttpBytesGetter,
    private val timeoutMs: Long,
    private val onBytes: (Long) -> Unit = {},
    private val diagLabel: String = "LIVE",
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        HttpBytesDataSource(getter, timeoutMs, onBytes, diagLabel)
}
