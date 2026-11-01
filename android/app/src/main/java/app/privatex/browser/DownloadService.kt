package app.privatex.browser

import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.support.utils.DownloadFileUtils
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.filewriter.DefaultDownloadFileWriter
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils

/** Downloads run through Mozilla's fetch download service (pause/resume/retry in the store). */
class DownloadService : AbstractFetchDownloadService() {
    override val store: BrowserStore by lazy { components.store }
    override val packageNameProvider: PackageNameProvider by lazy { DefaultPackageNameProvider(this) }
    override val notificationsDelegate: NotificationsDelegate by lazy { components.notificationsDelegate }
    override val httpClient: Client by lazy { components.fetchClient }
    override val fileSizeFormatter: FileSizeFormatter by lazy { DefaultFileSizeFormatter(this) }
    override val downloadEstimator: DownloadEstimator by lazy { DownloadEstimator(DefaultDateTimeProvider()) }
    override val downloadFileUtils: DownloadFileUtils by lazy { DefaultDownloadFileUtils(this) }
    override val downloadFileWriter: DownloadFileWriter by lazy {
        DefaultDownloadFileWriter(this, DefaultDownloadFileUtils(this))
    }
}
