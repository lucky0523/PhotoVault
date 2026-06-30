package com.photovault.ui.main.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.api.FileApi
import com.photovault.data.api.model.DirectoryInfo
import com.photovault.data.api.model.FileBrowseInfo
import com.photovault.data.local.CredentialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val isEmpty: Boolean = false
)

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
                        _uiState.value = _uiState.value.copy(
                            currentPath = listing.currentPath,
                            breadcrumbs = breadcrumbs,
                            directories = listing.directories,
                            files = listing.files,
                            isLoading = false,
                            isRefreshing = false,
                            isEmpty = listing.directories.isEmpty() && listing.files.isEmpty()
                        )
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
