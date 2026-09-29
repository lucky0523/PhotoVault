package com.huoyi.photovault.ui.main.tabs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huoyi.photovault.R
import com.huoyi.photovault.data.api.FileApi
import com.huoyi.photovault.data.api.model.DirectoryInfo
import com.huoyi.photovault.data.api.model.FileBrowseInfo
import com.huoyi.photovault.data.api.model.TrashItemInfo
import com.huoyi.photovault.data.local.CredentialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Which view the Cloud Tab is currently showing: the directory browser or the
 * recycle bin. The trash view is reached via a pinned entry row at the top of
 * the root directory listing.
 */
enum class CloudViewMode { Browse, Trash }

/**
 * UI state for the Cloud Tab.
 */
data class CloudTabUiState(
    val currentPath: String = "/",
    val breadcrumbs: List<BreadcrumbItem> = listOf(BreadcrumbItem("/", "/")),
    val directories: List<DirectoryInfo> = emptyList(),
    val files: List<FileBrowseInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,
    // File pagination. The server pages only the `files` array (`directories`
    // always come back complete), so these track how much of the current
    // directory's file list has been pulled in so far.
    val totalFiles: Int = 0,
    val loadedPages: Int = 0,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    // Recycle bin
    val viewMode: CloudViewMode = CloudViewMode.Browse,
    val trashTotal: Int = 0,
    val trashItems: List<TrashItemInfo> = emptyList(),
    val isTrashLoading: Boolean = false,
    val trashError: String? = null,
    // Multi-select (entered by long-pressing a file in the browser)
    val selectedFileIds: Set<Int> = emptySet(),
    val isMovingToTrash: Boolean = false,
    /** One-shot user feedback (shown as a Toast, then cleared via [CloudTabViewModel.consumeMessage]). */
    val message: String? = null
) {
    /** True while the browser is in multi-select mode. */
    val isSelectionMode: Boolean get() = selectedFileIds.isNotEmpty()

    /**
     * True when the pinned recycle-bin entry row should be shown (root only).
     * The server normalizes the root path to an empty string (it strips slashes),
     * so treat both "" and "/" as root.
     */
    val showTrashEntry: Boolean get() = currentPath.isBlank() || currentPath == "/"

    /** True while the current directory still has un-fetched files on the server. */
    val hasMoreFiles: Boolean get() = files.size < totalFiles
}

/**
 * Represents a segment in the breadcrumb navigation.
 */
data class BreadcrumbItem(
    val path: String,
    val label: String
)

/**
 * ViewModel for the Cloud Tab.
 * Manages navigation state (current path, breadcrumbs) and fetches
 * directory/file data from the server API.
 */
@HiltViewModel
class CloudTabViewModel @Inject constructor(
    private val fileApi: FileApi,
    private val credentialManager: CredentialManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudTabUiState())
    val uiState: StateFlow<CloudTabUiState> = _uiState.asStateFlow()

    private var directoryLoadJob: Job? = null
    private var browseGeneration = 0L

    /**
     * Returns the base URL for constructing thumbnail and download URLs.
     */
    val serverBaseUrl: String
        get() {
            val address = credentialManager.getServerAddress() ?: "http://localhost:8000"
            var url = address.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }

    init {
        loadDirectory("/")
    }

    /**
     * Load the first page of a directory's contents from the server, replacing
     * any previously loaded listing. Subsequent pages are pulled in by
     * [loadMoreFiles] as the user scrolls or swipes through the preview.
     */
    fun loadDirectory(path: String) {
        val generation = ++browseGeneration
        directoryLoadJob?.cancel()
        // A selection only makes sense within the directory it was made in.
        val keepSelection = path == _uiState.value.currentPath
        _uiState.value = _uiState.value.copy(
            selectedFileIds = if (keepSelection) _uiState.value.selectedFileIds else emptySet(),
            currentPath = path,
            breadcrumbs = buildBreadcrumbs(path),
            isLoading = true,
            isLoadingMore = false,
            loadMoreError = null,
            error = null
        )

        directoryLoadJob = viewModelScope.launch {
            try {
                val response = fileApi.browseDirectory(
                    path = path,
                    page = 1,
                    pageSize = FILE_PAGE_SIZE
                )
                if (generation != browseGeneration) return@launch

                if (response.isSuccessful) {
                    val listing = response.body()
                    if (listing != null) {
                        val breadcrumbs = buildBreadcrumbs(listing.currentPath)
                        val visibleDirectories = listing.directories.filterNot { it.name == ".trash" }
                        _uiState.value = _uiState.value.copy(
                            currentPath = listing.currentPath,
                            breadcrumbs = breadcrumbs,
                            directories = visibleDirectories,
                            files = listing.files,
                            totalFiles = listing.totalFiles,
                            loadedPages = 1,
                            isEmpty = visibleDirectories.isEmpty() && listing.files.isEmpty()
                        )
                        if (listing.currentPath.isBlank() || listing.currentPath == "/") {
                            refreshTrashCount()
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            error = context.getString(R.string.error_empty_server_response)
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = context.getString(R.string.error_load_http, response.code())
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (generation == browseGeneration) {
                    _uiState.value = _uiState.value.copy(
                        error = context.getString(R.string.error_network)
                    )
                }
            } finally {
                // A cancelled older request must not hide the spinner belonging
                // to the newer directory request.
                if (generation == browseGeneration) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false
                    )
                    directoryLoadJob = null
                }
            }
        }
    }

    /**
     * Navigate into a subdirectory.
     */
    fun navigateToDirectory(directoryPath: String) {
        loadDirectory(directoryPath)
    }

    /**
     * Navigate to a specific breadcrumb path.
     */
    fun navigateToBreadcrumb(breadcrumb: BreadcrumbItem) {
        loadDirectory(breadcrumb.path)
    }

    /**
     * Pull-to-refresh: reload the current directory.
     */
    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        loadDirectory(_uiState.value.currentPath)
    }

    /**
     * Append the next page of files for the current directory.
     *
     * The server caps `page_size` at 200, so a folder with more files than that
     * needs several round trips; without this the browser (and the full-screen
     * preview built from the same list) would only ever see the first page.
     *
     * Safe to call repeatedly: it no-ops while a load is already in flight,
     * when the directory is fully loaded, or while the trash view is showing.
     */
    fun loadMoreFiles() {
        val state = _uiState.value
        if (state.viewMode != CloudViewMode.Browse) return
        if (state.isLoading || state.isLoadingMore || !state.hasMoreFiles) return

        val path = state.currentPath
        val nextPage = state.loadedPages + 1
        val generation = browseGeneration

        // Flip the in-flight flag synchronously (this is always called from the
        // main thread) so back-to-back triggers from the scroll listener and the
        // preview pager can't both start the same request.
        _uiState.value = state.copy(isLoadingMore = true, loadMoreError = null)

        viewModelScope.launch {
            try {
                val response = fileApi.browseDirectory(
                    path = path,
                    page = nextPage,
                    pageSize = FILE_PAGE_SIZE
                )
                val listing = response.body()
                val current = _uiState.value

                // The user may have navigated away or refreshed while the request
                // was in flight — in that case the payload no longer applies.
                if (generation != browseGeneration ||
                    current.currentPath != path || current.loadedPages != nextPage - 1
                ) {
                    return@launch
                }

                if (!response.isSuccessful || listing == null) {
                    _uiState.value = current.copy(
                        isLoadingMore = false,
                        loadMoreError = if (!response.isSuccessful) {
                            context.getString(R.string.error_load_http, response.code())
                        } else {
                            context.getString(R.string.error_empty_server_response)
                        }
                    )
                    return@launch
                }

                if (listing.files.isEmpty()) {
                    // Nothing at this offset (files removed since the count was
                    // taken). Clamp the total so we stop asking for more.
                    _uiState.value = current.copy(
                        totalFiles = current.files.size,
                        isLoadingMore = false
                    )
                    return@launch
                }

                // Guard against overlap between pages (e.g. an upload shifted the
                // ordering) so the pager never shows the same photo twice.
                val seenIds = current.files.mapTo(HashSet()) { it.id }
                val appended = listing.files.filter { seenIds.add(it.id) }

                _uiState.value = current.copy(
                    files = current.files + appended,
                    totalFiles = listing.totalFiles,
                    loadedPages = nextPage,
                    isLoadingMore = false,
                    loadMoreError = null
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (generation == browseGeneration && _uiState.value.currentPath == path) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingMore = false,
                        loadMoreError = context.getString(R.string.error_network)
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Recycle bin
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Multi-select → move to trash
    // ------------------------------------------------------------------

    /** Add/remove a file from the selection (the first add enters selection mode). */
    fun toggleFileSelection(fileId: Int) {
        val state = _uiState.value
        if (state.isMovingToTrash) return
        val selected = state.selectedFileIds
        _uiState.value = state.copy(
            selectedFileIds = if (fileId in selected) selected - fileId else selected + fileId
        )
    }

    /** Leave selection mode without doing anything. */
    fun clearSelection() {
        if (_uiState.value.isMovingToTrash) return
        _uiState.value = _uiState.value.copy(selectedFileIds = emptySet())
    }

    /** Clear the one-shot [CloudTabUiState.message] after it has been shown. */
    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * Move every selected file to the recycle bin (server soft-delete).
     *
     * The server has no batch endpoint, so files are removed one by one. Files
     * that were moved are dropped from the loaded listing in place (keeping the
     * scroll position and already-fetched pages); failed ones stay selected so
     * the user can retry.
     */
    fun moveSelectedToTrash() {
        val state = _uiState.value
        if (state.isMovingToTrash || state.selectedFileIds.isEmpty()) return
        val ids = state.selectedFileIds.toList()
        val path = state.currentPath
        _uiState.value = state.copy(isMovingToTrash = true)

        viewModelScope.launch {
            val moved = HashSet<Int>()
            var networkFailure = false
            for (id in ids) {
                try {
                    val response = fileApi.moveFileToTrash(id)
                    // 404 = already gone (e.g. deleted from the web UI); treat
                    // it as done so it doesn't linger in the list.
                    if (response.isSuccessful || response.code() == 404) moved += id
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    networkFailure = true
                }
            }

            val current = _uiState.value
            val failedCount = ids.size - moved.size
            val sameDirectory = current.currentPath == path
            val remainingFiles =
                if (sameDirectory) current.files.filterNot { it.id in moved } else current.files
            _uiState.value = current.copy(
                files = remainingFiles,
                totalFiles = if (sameDirectory) {
                    (current.totalFiles - (current.files.size - remainingFiles.size)).coerceAtLeast(0)
                } else {
                    current.totalFiles
                },
                isEmpty = if (sameDirectory) {
                    current.directories.isEmpty() && remainingFiles.isEmpty()
                } else {
                    current.isEmpty
                },
                selectedFileIds = current.selectedFileIds.filterTo(HashSet()) { it !in moved },
                isMovingToTrash = false,
                message = when {
                    failedCount == 0 -> "已将 ${moved.size} 张图片移入回收站"
                    networkFailure -> "$failedCount 张图片移入回收站失败，请检查网络后重试"
                    else -> "$failedCount 张图片移入回收站失败"
                }
            )
            if (moved.isNotEmpty()) refreshTrashCount()
        }
    }

    /** Enter the recycle-bin view and (re)load its contents. */
    fun enterTrash() {
        _uiState.value = _uiState.value.copy(
            viewMode = CloudViewMode.Trash,
            selectedFileIds = emptySet()
        )
        loadTrash()
    }

    /** Return from the recycle-bin view to the directory browser. */
    fun exitTrash() {
        _uiState.value = _uiState.value.copy(viewMode = CloudViewMode.Browse)
        loadDirectory(_uiState.value.currentPath)
    }

    /**
     * Best-effort refresh of the trash item count for the pinned entry badge.
     * Failures are swallowed so they never block the directory browser.
     */
    private fun refreshTrashCount() {
        viewModelScope.launch {
            try {
                val response = fileApi.listTrash(page = 1, pageSize = 1)
                if (response.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        trashTotal = response.body()?.total ?: 0
                    )
                }
            } catch (_: Exception) {
                // Ignore — the badge simply keeps its previous value.
            }
        }
    }

    /** Load the full recycle-bin listing for the trash view. */
    fun loadTrash() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTrashLoading = true, trashError = null)
            try {
                val response = fileApi.listTrash()
                if (response.isSuccessful) {
                    val body = response.body()
                    _uiState.value = _uiState.value.copy(
                        trashItems = body?.items ?: emptyList(),
                        trashTotal = body?.total ?: 0,
                        isTrashLoading = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isTrashLoading = false,
                        trashError = context.getString(R.string.error_load_http, response.code())
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTrashLoading = false,
                    trashError = context.getString(R.string.error_network)
                )
            }
        }
    }

    /** Restore a file from trash; on success reload the trash listing. */
    fun restoreFile(fileId: Int) {
        viewModelScope.launch {
            try {
                val response = fileApi.restoreTrashFile(fileId)
                if (response.isSuccessful) {
                    loadTrash()
                } else {
                    _uiState.value = _uiState.value.copy(
                        trashError = context.getString(R.string.error_load_http, response.code())
                    )
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    trashError = context.getString(R.string.error_network)
                )
            }
        }
    }

    /** Permanently delete a file from trash; on success reload the trash listing. */
    fun purgeFile(fileId: Int) {
        viewModelScope.launch {
            try {
                val response = fileApi.purgeTrashFile(fileId)
                if (response.isSuccessful) {
                    loadTrash()
                } else {
                    _uiState.value = _uiState.value.copy(
                        trashError = context.getString(R.string.error_load_http, response.code())
                    )
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    trashError = context.getString(R.string.error_network)
                )
            }
        }
    }

    /**
     * Build breadcrumb items from a path string.
     * E.g., "/alice/Pixel9Pro/DCIM" becomes:
     *   [("/", "/"), ("/alice", "alice"), ("/alice/Pixel9Pro", "Pixel9Pro"), ("/alice/Pixel9Pro/DCIM", "DCIM")]
     */
    private fun buildBreadcrumbs(path: String): List<BreadcrumbItem> {
        val breadcrumbs = mutableListOf(BreadcrumbItem("/", "/"))

        if (path == "/" || path.isEmpty()) {
            return breadcrumbs
        }

        val normalizedPath = path.trimEnd('/')
        val segments = normalizedPath.split("/").filter { it.isNotEmpty() }
        var currentPath = ""

        for (segment in segments) {
            currentPath = "$currentPath/$segment"
            breadcrumbs.add(BreadcrumbItem(currentPath, segment))
        }

        return breadcrumbs
    }

    private companion object {
        /**
         * Files fetched per `/api/v1/files/browse` request. This is the server's
         * hard maximum (`page_size` is validated with `le=200`); anything larger
         * is rejected with HTTP 422.
         */
        const val FILE_PAGE_SIZE = 200
    }
}
