package com.photovault.ui.main.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.api.FileApi
import com.photovault.data.api.model.DirectoryInfo
import com.photovault.data.api.model.FileBrowseInfo
import com.photovault.data.api.model.TrashItemInfo
import com.photovault.data.local.CredentialManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
    // Recycle bin
    val viewMode: CloudViewMode = CloudViewMode.Browse,
    val trashTotal: Int = 0,
    val trashItems: List<TrashItemInfo> = emptyList(),
    val isTrashLoading: Boolean = false,
    val trashError: String? = null
) {
    /**
     * True when the pinned recycle-bin entry row should be shown (root only).
     * The server normalizes the root path to an empty string (it strips slashes),
     * so treat both "" and "/" as root.
     */
    val showTrashEntry: Boolean get() = currentPath.isBlank() || currentPath == "/"
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
    private val credentialManager: CredentialManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudTabUiState())
    val uiState: StateFlow<CloudTabUiState> = _uiState.asStateFlow()

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
     * Load the contents of a directory from the server.
     */
    fun loadDirectory(path: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null
            )

            try {
                val response = fileApi.browseDirectory(path = path)
                if (response.isSuccessful) {
                    val listing = response.body()
                    if (listing != null) {
                        val breadcrumbs = buildBreadcrumbs(listing.currentPath)
                        // Hide the .trash folder from the cloud browser.
                        val visibleDirectories = listing.directories.filterNot { it.name == ".trash" }
                        _uiState.value = _uiState.value.copy(
                            currentPath = listing.currentPath,
                            breadcrumbs = breadcrumbs,
                            directories = visibleDirectories,
                            files = listing.files,
                            isLoading = false,
                            isRefreshing = false,
                            isEmpty = visibleDirectories.isEmpty() && listing.files.isEmpty()
                        )
                        // Keep the pinned trash-entry badge count fresh while at root
                        // (server normalizes root to "").
                        if (listing.currentPath.isBlank() || listing.currentPath == "/") {
                            refreshTrashCount()
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = "服务器返回空数据"
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = "加载失败: ${response.code()}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = "网络错误: ${e.localizedMessage ?: "未知错误"}"
                )
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            loadDirectory(_uiState.value.currentPath)
        }
    }

    // ------------------------------------------------------------------
    // Recycle bin
    // ------------------------------------------------------------------

    /** Enter the recycle-bin view and (re)load its contents. */
    fun enterTrash() {
        _uiState.value = _uiState.value.copy(viewMode = CloudViewMode.Trash)
        loadTrash()
    }

    /** Return from the recycle-bin view to the directory browser. */
    fun exitTrash() {
        _uiState.value = _uiState.value.copy(viewMode = CloudViewMode.Browse)
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
                        trashError = "加载失败: ${response.code()}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTrashLoading = false,
                    trashError = "网络错误: ${e.localizedMessage ?: "未知错误"}"
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
                }
            } catch (_: Exception) {
                // Keep current listing; user can retry.
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
                }
            } catch (_: Exception) {
                // Keep current listing; user can retry.
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
}
