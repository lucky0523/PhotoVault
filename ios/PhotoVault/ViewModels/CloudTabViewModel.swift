import Foundation
import Combine

// MARK: - API Response Models

/// Represents a directory entry from the browse API
struct DirectoryInfo: Codable, Identifiable {
    let name: String
    let path: String
    let fileCount: Int
    let latestFileTime: String?

    var id: String { path }

    enum CodingKeys: String, CodingKey {
        case name
        case path
        case fileCount = "file_count"
        case latestFileTime = "latest_file_time"
    }
}

/// Represents a file entry from the browse API
struct FileBrowseInfo: Codable, Identifiable {
    let id: Int
    let fileName: String
    let fileSize: Int
    let mimeType: String?
    let exifTime: String?
    let thumbnailUrl: String
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case fileName = "file_name"
        case fileSize = "file_size"
        case mimeType = "mime_type"
        case exifTime = "exif_time"
        case thumbnailUrl = "thumbnail_url"
        case createdAt = "created_at"
    }
}

/// The response from GET /api/v1/files/browse
struct DirectoryListing: Codable {
    let currentPath: String
    let parentPath: String?
    let directories: [DirectoryInfo]
    let files: [FileBrowseInfo]
    let totalFiles: Int
    let page: Int
    let pageSize: Int

    enum CodingKeys: String, CodingKey {
        case currentPath = "current_path"
        case parentPath = "parent_path"
        case directories
        case files
        case totalFiles = "total_files"
        case page
        case pageSize = "page_size"
    }
}

// MARK: - Cloud Tab View Model

/// ViewModel for the Cloud Tab, manages server-side directory browsing.
@MainActor
class CloudTabViewModel: ObservableObject {
    // MARK: - Published Properties

    @Published var currentPath: String = "/"
    @Published var directories: [DirectoryInfo] = []
    @Published var files: [FileBrowseInfo] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String? = nil

    // MARK: - Computed Properties

    /// Breadcrumb path components derived from currentPath
    var breadcrumbComponents: [(name: String, path: String)] {
        var components: [(name: String, path: String)] = [("/", "/")]

        guard currentPath != "/" else { return components }

        let parts = currentPath
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .split(separator: "/")
            .map(String.init)

        var accumulatedPath = ""
        for part in parts {
            accumulatedPath += "/\(part)"
            components.append((part, accumulatedPath))
        }

        return components
    }

    /// Whether the directory listing is empty (no folders and no files)
    var isEmpty: Bool {
        return directories.isEmpty && files.isEmpty
    }

    // MARK: - Dependencies

    private let apiClient: APIClient

    // MARK: - Initialization

    init(apiClient: APIClient = .shared) {
        self.apiClient = apiClient
    }

    // MARK: - Public Methods

    /// Load directory contents at the given path
    func loadDirectory(path: String? = nil) async {
        let targetPath = path ?? currentPath
        isLoading = true
        errorMessage = nil

        do {
            let queryItems = [URLQueryItem(name: "path", value: targetPath)]
            let listing: DirectoryListing = try await apiClient.request(
                endpoint: "/api/v1/files/browse",
                method: .get,
                queryItems: queryItems
            )

            currentPath = listing.currentPath
            directories = listing.directories
            files = listing.files
        } catch let error as APIError {
            errorMessage = error.errorDescription
        } catch {
            errorMessage = "加载失败: \(error.localizedDescription)"
        }

        isLoading = false
    }

    /// Navigate into a subfolder by name
    func navigateToFolder(name: String) async {
        let newPath: String
        if currentPath == "/" {
            newPath = "/\(name)"
        } else {
            newPath = "\(currentPath)/\(name)"
        }
        await loadDirectory(path: newPath)
    }

    /// Navigate back to a specific path (breadcrumb tap)
    func navigateBack(to path: String) async {
        await loadDirectory(path: path)
    }

    /// Build the thumbnail URL for a file
    func thumbnailURL(for file: FileBrowseInfo) -> URL? {
        let baseURL = apiClient.currentBaseURL
        guard !baseURL.isEmpty else { return nil }
        return URL(string: "\(baseURL)/api/v1/files/thumbnail/\(file.id)?size=small")
    }

    /// Build the download URL for a file (full resolution)
    func downloadURL(for file: FileBrowseInfo) -> URL? {
        let baseURL = apiClient.currentBaseURL
        guard !baseURL.isEmpty else { return nil }
        return URL(string: "\(baseURL)/api/v1/files/download/\(file.id)")
    }

    /// Format file size for display
    func formattedFileSize(_ bytes: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }

    /// Format date string for display
    func formattedDate(_ dateString: String?) -> String {
        guard let dateString = dateString else { return "" }

        let isoFormatter = ISO8601DateFormatter()
        isoFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        let displayFormatter = DateFormatter()
        displayFormatter.dateStyle = .short
        displayFormatter.timeStyle = .short
        displayFormatter.locale = Locale(identifier: "zh_CN")

        if let date = isoFormatter.date(from: dateString) {
            return displayFormatter.string(from: date)
        }

        // Try without fractional seconds
        isoFormatter.formatOptions = [.withInternetDateTime]
        if let date = isoFormatter.date(from: dateString) {
            return displayFormatter.string(from: date)
        }

        // Fallback: return raw string truncated
        return String(dateString.prefix(16))
    }
}
