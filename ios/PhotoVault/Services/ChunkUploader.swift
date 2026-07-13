import Foundation
import Photos
import CommonCrypto
import Combine
import CoreData

// MARK: - Upload State

/// Represents the current state of an upload operation
enum ChunkUploadState: String {
    case idle = "idle"
    case hashing = "hashing"
    case checkingDuplicate = "checking_duplicate"
    case initializing = "initializing"
    case uploading = "uploading"
    case completing = "completing"
    case completed = "completed"
    case paused = "paused"
    case failed = "failed"
    case skippedDuplicate = "skipped_duplicate"
}

// MARK: - Upload Progress Info

/// Published progress information for UI consumption
struct UploadProgressInfo: Equatable {
    let fileName: String
    let progress: Double          // 0.0 - 1.0
    let uploadSpeed: Double       // bytes per second
    let currentChunk: Int
    let totalChunks: Int
    let state: ChunkUploadState
}

// MARK: - Upload Result

/// Result of an upload operation
enum ChunkUploadResult {
    case success(fileId: String, storedPath: String)
    case duplicate(fileId: String?)
    case failed(message: String, shouldRetry: Bool)
    case paused
}

// MARK: - API Response Models

struct DuplicateCheckRequest: Codable {
    let fileHash: String
    let filePath: String
    let deviceName: String

    enum CodingKeys: String, CodingKey {
        case fileHash = "file_hash"
        case filePath = "file_path"
        case deviceName = "device_name"
    }
}

struct DuplicateCheckResponse: Codable {
    let isDuplicate: Bool
    let fileId: String?

    enum CodingKeys: String, CodingKey {
        case isDuplicate = "is_duplicate"
        case fileId = "file_id"
    }
}

struct InitUploadRequest: Codable {
    let fileHash: String
    let fileName: String
    let fileSize: Int64
    let filePath: String
    let deviceName: String
    let sourceFolder: String
    let storagePolicy: StoragePolicyDTO
    let exifTime: String?
    let fileModifiedTime: String

    enum CodingKeys: String, CodingKey {
        case fileHash = "file_hash"
        case fileName = "file_name"
        case fileSize = "file_size"
        case filePath = "file_path"
        case deviceName = "device_name"
        case sourceFolder = "source_folder"
        case storagePolicy = "storage_policy"
        case exifTime = "exif_time"
        case fileModifiedTime = "file_modified_time"
    }
}

struct StoragePolicyDTO: Codable {
    let useCustomPath: Bool
    let customPath: String?
    let useYearMonthLayer: Bool

    enum CodingKeys: String, CodingKey {
        case useCustomPath = "use_custom_path"
        case customPath = "custom_path"
        case useYearMonthLayer = "use_year_month_layer"
    }
}

struct InitUploadResponse: Codable {
    let sessionId: String
    let totalChunks: Int
    let chunkSize: Int

    enum CodingKeys: String, CodingKey {
        case sessionId = "session_id"
        case totalChunks = "total_chunks"
        case chunkSize = "chunk_size"
    }
}

struct ChunkUploadResponse: Codable {
    let chunkIndex: Int
    let received: Bool
    let checksumValid: Bool

    enum CodingKeys: String, CodingKey {
        case chunkIndex = "chunk_index"
        case received
        case checksumValid = "checksum_valid"
    }
}

struct CompleteUploadRequest: Codable {
    let sessionId: String
    let fileHash: String

    enum CodingKeys: String, CodingKey {
        case sessionId = "session_id"
        case fileHash = "file_hash"
    }
}

struct CompleteUploadResponse: Codable {
    let success: Bool
    let fileId: String
    let storedPath: String

    enum CodingKeys: String, CodingKey {
        case success
        case fileId = "file_id"
        case storedPath = "stored_path"
    }
}

struct ResumeInfoResponse: Codable {
    let sessionId: String
    let receivedChunks: [Int]
    let totalChunks: Int
    let fileHash: String
    let expiresAt: String

    enum CodingKeys: String, CodingKey {
        case sessionId = "session_id"
        case receivedChunks = "received_chunks"
        case totalChunks = "total_chunks"
        case fileHash = "file_hash"
        case expiresAt = "expires_at"
    }
}

// MARK: - Chunk Uploader

/// Handles chunked file upload with resume capability and condition-aware pausing.
///
/// Upload flow:
/// 1. Fetch PHAsset data from photo library
/// 2. Calculate SHA-256 file hash
/// 3. Check for duplicates on server
/// 4. Initialize upload session (or resume existing)
/// 5. Split file into 2MB chunks
/// 6. Upload chunks sequentially with MD5 checksum per chunk
/// 7. After all chunks: complete upload
/// 8. Before each chunk: check backup conditions, pause if not met
/// 9. On failure: retry up to 3 times with 30s delay
class ChunkUploader: ObservableObject {
    // MARK: - Constants

    static let chunkSize = 2 * 1024 * 1024 // 2MB
    static let maxRetries = 3
    static let retryDelaySeconds: TimeInterval = 30
    static let sessionExpireDays = 7

    // MARK: - Published State

    @Published private(set) var currentProgress: UploadProgressInfo?
    @Published private(set) var uploadState: ChunkUploadState = .idle
    @Published private(set) var isPaused: Bool = false
    @Published private(set) var queueCount: Int = 0
    @Published private(set) var completedCount: Int = 0

    // MARK: - Properties

    private let conditionChecker: BackupConditionChecker
    private let persistenceController: PersistenceController
    private var cancellables = Set<AnyCancellable>()
    private var pauseContinuation: CheckedContinuation<Void, Never>?
    private var uploadStartTime: Date?

    // MARK: - Singleton

    static let shared = ChunkUploader()

    // MARK: - Initialization

    init(
        conditionChecker: BackupConditionChecker = .shared,
        persistenceController: PersistenceController = .shared
    ) {
        self.conditionChecker = conditionChecker
        self.persistenceController = persistenceController
        setupConditionListener()
    }

    // MARK: - File Operations

    /// Calculate SHA-256 hash of file data
    /// - Parameter data: The file data to hash
    /// - Returns: Hex-encoded SHA-256 hash string
    func calculateSHA256(for data: Data) -> String {
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { buffer in
            _ = CC_SHA256(buffer.baseAddress, CC_LONG(data.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    /// Split file data into chunks of specified size
    /// - Parameters:
    ///   - data: The file data to split
    ///   - chunkSize: Size of each chunk in bytes (default: 2MB)
    /// - Returns: Array of data chunks
    func splitIntoChunks(data: Data, chunkSize: Int = ChunkUploader.chunkSize) -> [Data] {
        var chunks: [Data] = []
        let totalSize = data.count
        var offset = 0

        while offset < totalSize {
            let length = min(chunkSize, totalSize - offset)
            let chunk = data.subdata(in: offset..<(offset + length))
            chunks.append(chunk)
            offset += length
        }

        return chunks
    }

    /// Calculate MD5 checksum for a chunk of data
    /// - Parameter chunk: The chunk data
    /// - Returns: Hex-encoded MD5 hash string
    func calculateMD5(for chunk: Data) -> String {
        var hash = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
        chunk.withUnsafeBytes { buffer in
            _ = CC_MD5(buffer.baseAddress, CC_LONG(chunk.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Upload Flow

    /// Upload a file represented by an UploadRecord
    /// - Parameter record: The UploadRecord from Core Data containing file info
    /// - Returns: ChunkUploadResult indicating the outcome
    func uploadFile(record: UploadRecord) async -> ChunkUploadResult {
        uploadStartTime = Date()

        // Step 1: Fetch PHAsset data from photo library
        guard let fileData = await fetchAssetData(localIdentifier: record.localFilePath) else {
            return .failed(message: "无法读取照片数据", shouldRetry: false)
        }

        await updateState(.hashing)
        await updateProgress(
            fileName: record.fileName,
            progress: 0,
            speed: 0,
            currentChunk: 0,
            totalChunks: 0,
            state: .hashing
        )

        // Step 2: Calculate SHA-256 file hash
        let fileHash = calculateSHA256(for: fileData)

        // Update the record's file hash
        await updateRecordHash(record: record, hash: fileHash, fileSize: Int64(fileData.count))

        // Step 3: Check for duplicates
        await updateState(.checkingDuplicate)
        await updateProgress(
            fileName: record.fileName,
            progress: 0,
            speed: 0,
            currentChunk: 0,
            totalChunks: 0,
            state: .checkingDuplicate
        )

        let duplicateResult = await checkDuplicate(fileHash: fileHash, filePath: record.localFilePath)
        if case .duplicate(let fileId) = duplicateResult {
            await markRecordAsSkipped(record: record)
            await updateState(.skippedDuplicate)
            return .duplicate(fileId: fileId)
        }

        // Step 4: Initialize or resume session
        await updateState(.initializing)
        await updateProgress(
            fileName: record.fileName,
            progress: 0,
            speed: 0,
            currentChunk: 0,
            totalChunks: 0,
            state: .initializing
        )

        let totalChunks = Int(ceil(Double(fileData.count) / Double(Self.chunkSize)))

        let sessionInfo: SessionInfo
        if let existingSessionId = record.sessionId, !existingSessionId.isEmpty {
            // Try to resume existing session
            if let resumed = await resolveResumeSession(
                record: record,
                fileData: fileData,
                fileHash: fileHash,
                totalChunks: totalChunks
            ) {
                sessionInfo = resumed
            } else {
                // Resume failed, init new session
                guard let newSession = await initNewSession(
                    record: record,
                    fileHash: fileHash,
                    fileSize: Int64(fileData.count),
                    totalChunks: totalChunks
                ) else {
                    return .failed(message: "无法初始化上传会话", shouldRetry: true)
                }
                sessionInfo = newSession
            }
        } else {
            // No existing session, init new one
            guard let newSession = await initNewSession(
                record: record,
                fileHash: fileHash,
                fileSize: Int64(fileData.count),
                totalChunks: totalChunks
            ) else {
                return .failed(message: "无法初始化上传会话", shouldRetry: true)
            }
            sessionInfo = newSession
        }

        // Update record with session info
        await updateRecordSession(
            record: record,
            sessionId: sessionInfo.sessionId,
            totalChunks: Int32(totalChunks)
        )

        // Step 5: Split file into chunks
        let chunks = splitIntoChunks(data: fileData)

        // Step 6: Upload chunks sequentially
        await updateState(.uploading)

        for chunkIndex in sessionInfo.startChunkIndex..<totalChunks {
            // Check backup conditions before each chunk
            if !conditionChecker.canBackup() {
                // Save progress and pause
                await saveProgress(record: record, uploadedChunks: Int32(chunkIndex))
                await updateState(.paused)
                await updateProgress(
                    fileName: record.fileName,
                    progress: Double(chunkIndex) / Double(totalChunks),
                    speed: 0,
                    currentChunk: chunkIndex,
                    totalChunks: totalChunks,
                    state: .paused
                )
                isPaused = true

                // Wait for conditions to restore
                await waitForConditionRestore()

                isPaused = false
                await updateState(.uploading)
            }

            // Calculate progress and speed
            let progress = Double(chunkIndex) / Double(totalChunks)
            let elapsed = Date().timeIntervalSince(uploadStartTime ?? Date())
            let uploadedBytes = Double(chunkIndex) * Double(Self.chunkSize)
            let speed = elapsed > 0 ? uploadedBytes / elapsed : 0

            await updateProgress(
                fileName: record.fileName,
                progress: progress,
                speed: speed,
                currentChunk: chunkIndex,
                totalChunks: totalChunks,
                state: .uploading
            )

            // Upload chunk with retry
            let chunkData = chunks[chunkIndex]
            let success = await uploadChunkWithRetry(
                sessionId: sessionInfo.sessionId,
                chunkIndex: chunkIndex,
                chunkData: chunkData
            )

            if !success {
                // Mark as failed after all retries exhausted
                await markRecordAsFailed(
                    record: record,
                    error: "分块 \(chunkIndex) 上传失败，已重试 \(Self.maxRetries) 次"
                )
                await updateState(.failed)
                return .failed(
                    message: "分块 \(chunkIndex) 上传失败，已重试 \(Self.maxRetries) 次",
                    shouldRetry: true
                )
            }

            // Update progress in Core Data
            await saveProgress(record: record, uploadedChunks: Int32(chunkIndex + 1))
        }

        // Step 7: Complete upload
        await updateState(.completing)
        await updateProgress(
            fileName: record.fileName,
            progress: 1.0,
            speed: 0,
            currentChunk: totalChunks,
            totalChunks: totalChunks,
            state: .completing
        )

        let completeResult = await completeUpload(
            sessionId: sessionInfo.sessionId,
            fileHash: fileHash
        )

        switch completeResult {
        case .success(let fileId, let storedPath):
            await markRecordAsCompleted(record: record)
            await updateState(.completed)
            await updateProgress(
                fileName: record.fileName,
                progress: 1.0,
                speed: 0,
                currentChunk: totalChunks,
                totalChunks: totalChunks,
                state: .completed
            )
            return .success(fileId: fileId, storedPath: storedPath)

        case .failed(let message, _):
            await markRecordAsFailed(record: record, error: message)
            await updateState(.failed)
            return .failed(message: message, shouldRetry: true)

        default:
            return .failed(message: "上传完成验证失败", shouldRetry: true)
        }
    }

    // MARK: - Resume Support

    /// Resolve whether an existing session can be resumed
    private func resolveResumeSession(
        record: UploadRecord,
        fileData: Data,
        fileHash: String,
        totalChunks: Int
    ) async -> SessionInfo? {
        guard let sessionId = record.sessionId else { return nil }

        // Check session expiry (7 days)
        let sevenDaysAgo = Date().addingTimeInterval(-TimeInterval(Self.sessionExpireDays * 24 * 60 * 60))
        let recordDate = record.updatedAt ?? record.createdAt
        if recordDate < sevenDaysAgo {
            // Session expired, must restart
            return nil
        }

        // Check source file modification
        if let storedModTime = record.fileModifiedTime {
            let currentModTime = await getAssetModificationDate(localIdentifier: record.localFilePath)
            if let currentModTime = currentModTime, currentModTime != storedModTime {
                // File modified since last upload attempt, restart
                return nil
            }
        }

        // Query server for received chunks
        do {
            let resumeInfo: ResumeInfoResponse = try await APIClient.shared.request(
                endpoint: "/api/v1/backup/resume/\(sessionId)",
                method: .get
            )

            let receivedChunks = resumeInfo.receivedChunks
            let startChunk = receivedChunks.isEmpty ? 0 : (receivedChunks.max()! + 1)

            return SessionInfo(sessionId: sessionId, startChunkIndex: startChunk)
        } catch {
            // Server doesn't recognize session or network error
            return nil
        }
    }

    // MARK: - Private Methods

    /// Initialize a new upload session on the server
    private func initNewSession(
        record: UploadRecord,
        fileHash: String,
        fileSize: Int64,
        totalChunks: Int
    ) async -> SessionInfo? {
        // Build storage policy from the backup folder
        let storagePolicy = StoragePolicyDTO(
            useCustomPath: record.backupFolder?.useCustomPath ?? false,
            customPath: record.backupFolder?.customPath,
            useYearMonthLayer: record.backupFolder?.useYearMonthLayer ?? false
        )

        let deviceName = getDeviceName()
        let sourceFolder = record.backupFolder?.folderPath ?? ""

        let initRequest = InitUploadRequest(
            fileHash: fileHash,
            fileName: record.fileName,
            fileSize: fileSize,
            filePath: record.localFilePath,
            deviceName: deviceName,
            sourceFolder: sourceFolder,
            storagePolicy: storagePolicy,
            exifTime: nil,
            fileModifiedTime: ISO8601DateFormatter().string(from: record.fileModifiedTime ?? Date())
        )

        do {
            let response: InitUploadResponse = try await APIClient.shared.request(
                endpoint: "/api/v1/backup/init",
                method: .post,
                body: initRequest
            )

            return SessionInfo(sessionId: response.sessionId, startChunkIndex: 0)
        } catch {
            print("[ChunkUploader] Failed to init session: \(error.localizedDescription)")
            return nil
        }
    }

    /// Check if file is a duplicate on the server
    private func checkDuplicate(fileHash: String, filePath: String) async -> ChunkUploadResult? {
        let deviceName = getDeviceName()
        let checkRequest = DuplicateCheckRequest(
            fileHash: fileHash,
            filePath: filePath,
            deviceName: deviceName
        )

        do {
            let response: DuplicateCheckResponse = try await withTimeout(seconds: 30) {
                try await APIClient.shared.request(
                    endpoint: "/api/v1/backup/check",
                    method: .post,
                    body: checkRequest
                )
            }

            if response.isDuplicate {
                return .duplicate(fileId: response.fileId)
            }
            return nil
        } catch {
            // Per requirement 6.5: if duplicate check times out or fails, treat as not backed up
            return nil
        }
    }

    /// Upload a single chunk with retry logic (3 retries, 30s delay)
    private func uploadChunkWithRetry(
        sessionId: String,
        chunkIndex: Int,
        chunkData: Data
    ) async -> Bool {
        let md5Checksum = calculateMD5(for: chunkData)

        for attempt in 1...Self.maxRetries {
            do {
                let success = try await uploadChunkMultipart(
                    sessionId: sessionId,
                    chunkIndex: chunkIndex,
                    checksum: md5Checksum,
                    chunkData: chunkData
                )

                if success {
                    return true
                }
            } catch {
                print("[ChunkUploader] Chunk \(chunkIndex) attempt \(attempt) failed: \(error.localizedDescription)")
            }

            // Wait before retrying (unless last attempt)
            if attempt < Self.maxRetries {
                try? await Task.sleep(nanoseconds: UInt64(Self.retryDelaySeconds * 1_000_000_000))
            }
        }

        return false
    }

    /// Upload a chunk as multipart form data
    private func uploadChunkMultipart(
        sessionId: String,
        chunkIndex: Int,
        checksum: String,
        chunkData: Data
    ) async throws -> Bool {
        guard let baseURL = getBaseURL(), !baseURL.isEmpty else {
            throw APIError.invalidURL
        }

        guard let url = URL(string: "\(baseURL)/api/v1/backup/chunk") else {
            throw APIError.invalidURL
        }

        let boundary = UUID().uuidString
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        // Add auth token
        if let token = TokenManager.shared.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        // Build multipart body
        var body = Data()

        // session_id field
        body.appendMultipartField(name: "session_id", value: sessionId, boundary: boundary)

        // chunk_index field
        body.appendMultipartField(name: "chunk_index", value: "\(chunkIndex)", boundary: boundary)

        // checksum field
        body.appendMultipartField(name: "checksum", value: checksum, boundary: boundary)

        // chunk_data file field
        body.appendMultipartFile(name: "chunk_data", filename: "chunk_\(chunkIndex)", data: chunkData, boundary: boundary)

        // Closing boundary
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            return false
        }

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let chunkResponse = try decoder.decode(ChunkUploadResponse.self, from: data)

        return chunkResponse.received && chunkResponse.checksumValid
    }

    /// Complete the upload session
    private func completeUpload(sessionId: String, fileHash: String) async -> ChunkUploadResult {
        let completeRequest = CompleteUploadRequest(sessionId: sessionId, fileHash: fileHash)

        do {
            let response: CompleteUploadResponse = try await APIClient.shared.request(
                endpoint: "/api/v1/backup/complete",
                method: .post,
                body: completeRequest
            )

            // The server returns a 2xx with success=true only after verifying the
            // merged file's SHA-256; integrity failure surfaces as an HTTP 422,
            // which APIClient throws and we handle in the catch below.
            if response.success {
                return .success(fileId: response.fileId, storedPath: response.storedPath)
            } else {
                return .failed(message: "完成上传失败", shouldRetry: true)
            }
        } catch {
            return .failed(message: "完成上传请求失败: \(error.localizedDescription)", shouldRetry: true)
        }
    }

    // MARK: - PHAsset Data Fetching

    /// Fetch the full image data for a PHAsset by local identifier
    private func fetchAssetData(localIdentifier: String) async -> Data? {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = result.firstObject else { return nil }

        return await withCheckedContinuation { continuation in
            let options = PHImageRequestOptions()
            options.version = .original
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat

            PHImageManager.default().requestImageDataAndOrientation(
                for: asset,
                options: options
            ) { data, _, _, _ in
                continuation.resume(returning: data)
            }
        }
    }

    /// Get the modification date of a PHAsset
    private func getAssetModificationDate(localIdentifier: String) async -> Date? {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = result.firstObject else { return nil }
        return asset.modificationDate
    }

    // MARK: - Condition-Aware Behavior

    /// Set up listener for backup condition changes
    private func setupConditionListener() {
        conditionChecker.onConditionChanged = { [weak self] canBackup in
            guard let self = self else { return }
            if canBackup && self.isPaused {
                // Conditions restored, signal resume
                self.pauseContinuation?.resume()
                self.pauseContinuation = nil
            }
        }
    }

    /// Wait for backup conditions to be restored
    private func waitForConditionRestore() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            // If conditions are already met, resume immediately
            if conditionChecker.canBackup() {
                continuation.resume()
                return
            }
            // Otherwise, store the continuation and wait for condition change callback
            self.pauseContinuation = continuation
        }
    }

    // MARK: - Core Data Operations

    /// Update the record's file hash and size
    private func updateRecordHash(record: UploadRecord, hash: String, fileSize: Int64) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.fileHash = hash
            record.fileSize = fileSize
            record.updatedAt = Date()
            self.persistenceController.save()
        }
    }

    /// Update the record with session info
    private func updateRecordSession(record: UploadRecord, sessionId: String, totalChunks: Int32) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.sessionId = sessionId
            record.totalChunks = totalChunks
            record.status = UploadStatus.uploading.rawValue
            record.updatedAt = Date()
            self.persistenceController.save()
        }
    }

    /// Save upload progress (number of chunks uploaded)
    private func saveProgress(record: UploadRecord, uploadedChunks: Int32) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.uploadedChunks = uploadedChunks
            record.updatedAt = Date()
            self.persistenceController.save()
        }
    }

    /// Mark record as completed
    private func markRecordAsCompleted(record: UploadRecord) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.status = UploadStatus.completed.rawValue
            record.updatedAt = Date()
            self.persistenceController.save()
        }
    }

    /// Mark record as skipped (duplicate)
    private func markRecordAsSkipped(record: UploadRecord) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.status = UploadStatus.skipped.rawValue
            record.updatedAt = Date()
            self.persistenceController.save()
        }
    }

    /// Mark record as failed
    private func markRecordAsFailed(record: UploadRecord, error: String) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.status = UploadStatus.failed.rawValue
            record.errorMessage = error
            record.retryCount += 1
            record.updatedAt = Date()
            self.persistenceController.save()
        }
    }

    // MARK: - UI State Updates

    @MainActor
    private func updateState(_ state: ChunkUploadState) {
        self.uploadState = state
    }

    @MainActor
    private func updateProgress(
        fileName: String,
        progress: Double,
        speed: Double,
        currentChunk: Int,
        totalChunks: Int,
        state: ChunkUploadState
    ) {
        self.currentProgress = UploadProgressInfo(
            fileName: fileName,
            progress: progress,
            uploadSpeed: speed,
            currentChunk: currentChunk,
            totalChunks: totalChunks,
            state: state
        )
    }

    // MARK: - Helpers

    /// Get the device name (model identifier)
    private func getDeviceName() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machineMirror = Mirror(reflecting: systemInfo.machine)
        let identifier = machineMirror.children.reduce("") { identifier, element in
            guard let value = element.value as? Int8, value != 0 else { return identifier }
            return identifier + String(UnicodeScalar(UInt8(value)))
        }
        return identifier
    }

    /// Get the base URL from APIClient
    private func getBaseURL() -> String? {
        let url = APIClient.shared.currentBaseURL
        return url.isEmpty ? nil : url
    }

    /// Execute an async operation with a timeout
    private func withTimeout<T>(seconds: TimeInterval, operation: @escaping () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask {
                try await operation()
            }

            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw APIError.timeout
            }

            let result = try await group.next()!
            group.cancelAll()
            return result
        }
    }
}

// MARK: - Data Extension for Multipart

private extension Data {
    /// Append a text field to multipart form data
    mutating func appendMultipartField(name: String, value: String, boundary: String) {
        append("--\(boundary)\r\n".data(using: .utf8)!)
        append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".data(using: .utf8)!)
        append("\(value)\r\n".data(using: .utf8)!)
    }

    /// Append a file field to multipart form data
    mutating func appendMultipartFile(name: String, filename: String, data: Data, boundary: String) {
        append("--\(boundary)\r\n".data(using: .utf8)!)
        append("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!)
        append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        append(data)
        append("\r\n".data(using: .utf8)!)
    }
}

// MARK: - Session Info

private struct SessionInfo {
    let sessionId: String
    let startChunkIndex: Int
}
