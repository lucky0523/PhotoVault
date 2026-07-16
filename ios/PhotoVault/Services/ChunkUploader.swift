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

// MARK: - Pause Origin (R-24.5)

/// Distinguishes *why* an in-progress backup was paused.
///
/// Mirrors Android `enum class PauseReason { USER, CONDITION }` in
/// `BackupForegroundService`. It is intentionally separate from the
/// condition-detail `PauseReason` (电量不足 / 未连接WiFi …) defined in
/// `BackupConditionChecker`, because this only records the *source* of the pause:
/// - `.user`   — the user tapped "暂停"; must NOT be auto-resumed by condition
///               recovery. Only an explicit "开始" (resume) clears it.
/// - `.condition` — battery low / WiFi disconnected; auto-resumes once conditions
///               recover.
enum BackupPauseOrigin {
    case user
    case condition
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

    /// Merged "paused for any reason" flag (user OR condition). Mirrors Android
    /// `BackupForegroundService.isPaused`.
    @Published private(set) var isPaused: Bool = false
    @Published private(set) var queueCount: Int = 0
    @Published private(set) var completedCount: Int = 0

    /// Whether an upload is currently in progress. Mirrors Android
    /// `BackupForegroundService.isRunning`.
    @Published private(set) var isRunning: Bool = false

    // MARK: - Manual Pause State (R-24)

    /// `true` when the current pause was initiated by the user tapping "暂停".
    /// A user pause takes priority over condition recovery: it stays paused until
    /// the user taps "开始" (`resume()`); condition recovery must NOT override it
    /// (R-24.5). Mirrors Android `BackupForegroundService.isUserPaused`.
    @Published private(set) var isUserPaused: Bool = false

    /// The origin of the current pause (`.user` / `.condition`), or `nil` when not
    /// paused. Mirrors Android `BackupForegroundService.pauseReason`.
    @Published private(set) var pauseOrigin: BackupPauseOrigin?

    // MARK: - Run Source Tracking (R-3.13)

    /// The source of the run currently in progress. `true` when the active
    /// upload was initiated by an explicit user action (manual), `false` for an
    /// automatic trigger. Mirrors Android `BackupForegroundService.isManualRun`
    /// and is the deciding factor for the auto-backup toggle-off behavior
    /// (R-3.14/3.15). Published so the task view can label the button
    /// "正在手动/自动备份".
    @Published private(set) var isManualRun: Bool = false

    /// The id of the record currently being uploaded, so callers (e.g. the
    /// auto-backup toggle handler) can preserve its 断点续传 progress while
    /// clearing the rest of the queue.
    private(set) var currentRecordID: UUID?

    // MARK: - Properties

    private let conditionChecker: BackupConditionChecker
    private let persistenceController: PersistenceController
    private var cancellables = Set<AnyCancellable>()
    private var pauseContinuation: CheckedContinuation<Void, Never>?
    private var uploadStartTime: Date?

    /// Cooperative stop flag. Set by `stopAuto()` to halt an in-flight automatic
    /// run at the next chunk boundary while preserving resumable progress.
    private var stopRequested: Bool = false

    /// Cooperative user-pause flag. Set by `pause()` to park an in-flight run at
    /// the next chunk boundary (preserving 断点续传 progress) until the user taps
    /// "开始" (`resume()`). Unlike `stopRequested`, the queue is NOT cleared.
    private var userPauseRequested: Bool = false

    /// Continuation the upload loop parks on while user-paused; resumed by
    /// `resume()`. Kept separate from `pauseContinuation` (condition wait) so that
    /// condition recovery can never wake a user pause (R-24.5).
    private var userPauseContinuation: CheckedContinuation<Void, Never>?

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

    /// Returns whether a persisted resumable session belongs to different
    /// source bytes. Unknown legacy values (empty hash / zero size / nil date)
    /// do not by themselves invalidate a session, while every known mismatch
    /// forces a restart from chunk zero (R-32.2).
    static func hasSourceChanged(
        persistedHash: String,
        persistedSize: Int64,
        persistedModifiedTime: Date?,
        currentHash: String,
        currentSize: Int64,
        currentModifiedTime: Date?
    ) -> Bool {
        if !persistedHash.isEmpty && persistedHash != currentHash { return true }
        if persistedSize > 0 && persistedSize != currentSize { return true }
        if let persistedModifiedTime, let currentModifiedTime,
           persistedModifiedTime != currentModifiedTime {
            return true
        }
        return false
    }

    // MARK: - Upload Flow

    /// Upload a file represented by an UploadRecord.
    ///
    /// Records the run source (manual vs auto) for the duration of the upload so
    /// that a concurrent auto-backup toggle-off can decide whether to stop this
    /// task (R-3.13). Run bookkeeping (`isRunning`, `isManualRun`,
    /// `currentRecordID`, `stopRequested`) is set up before and torn down after
    /// the actual upload work regardless of how it terminates.
    /// - Parameter record: The UploadRecord from Core Data containing file info
    /// - Returns: ChunkUploadResult indicating the outcome
    func uploadFile(record: UploadRecord) async -> ChunkUploadResult {
        await beginRun(source: record.uploadSource, recordID: record.id)
        let result = await performUpload(record: record)
        await endRun()
        return result
    }

    /// Request that an in-flight **automatic** run stop as soon as possible,
    /// preserving the current file's 断点续传 progress (R-3.14).
    ///
    /// Called by the auto-backup toggle-off handler. The stop is cooperative:
    /// the upload loop checks `stopRequested` before each chunk, persists the
    /// chunks uploaded so far, marks the record back to `pending` (keeping its
    /// `sessionId`/`uploadedChunks` so it can resume when re-enabled), and exits.
    /// A manual run ignores this request and runs to completion (R-3.15).
    func stopAuto() {
        guard isRunning, !isManualRun else { return }
        stopRequested = true
        // Wake up a condition-wait so the loop can observe the stop flag.
        pauseContinuation?.resume()
        pauseContinuation = nil
    }

    /// User taps "暂停" (R-24.2). Marks the current run as a **user pause**,
    /// preserving 断点续传 progress. The upload loop parks at the next chunk
    /// boundary until `resume()` is called; the queue is left intact (unlike
    /// `stopAuto()`), and condition recovery will NOT auto-resume it (R-24.5).
    ///
    /// No-op unless a backup is actively in progress (not already paused), which
    /// matches the button semantics (the button only shows "暂停" while running).
    @MainActor
    func pause() {
        guard isRunning, !isPaused else { return }
        userPauseRequested = true
        isUserPaused = true
        isPaused = true
        pauseOrigin = .user
    }

    /// User taps "开始" (R-24.3). Clears the user pause and wakes the parked
    /// upload loop so it resumes from the persisted breakpoint (the loop re-checks
    /// `Backup_Condition` before the next chunk, so it only proceeds when
    /// conditions allow, otherwise it transitions to a condition wait).
    ///
    /// Only meaningful for a user pause; condition pauses resume automatically.
    @MainActor
    func resume() {
        guard isUserPaused else { return }
        isUserPaused = false
        userPauseRequested = false
        pauseOrigin = nil
        // Wake the parked loop; it will re-evaluate conditions before continuing.
        if let continuation = userPauseContinuation {
            userPauseContinuation = nil
            isPaused = false
            continuation.resume()
        } else {
            isPaused = false
        }
    }

    @MainActor
    private func beginRun(source: BackupSource, recordID: UUID) {
        isRunning = true
        isManualRun = source == .manual
        currentRecordID = recordID
        stopRequested = false
        userPauseRequested = false
        isUserPaused = false
        if pauseOrigin == .user { pauseOrigin = nil }
    }

    @MainActor
    private func endRun() {
        isRunning = false
        currentRecordID = nil
        stopRequested = false
        userPauseRequested = false
        isUserPaused = false
        if pauseOrigin == .user {
            pauseOrigin = nil
            isPaused = false
        }
    }

    /// Perform the actual chunked upload work for a record.
    private func performUpload(record: UploadRecord) async -> ChunkUploadResult {
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

        // Step 2: Calculate SHA-256 and validate the persisted source snapshot.
        // Capture the old values before updating the record; otherwise a changed
        // source could incorrectly reuse a session created for different bytes.
        let persistedHash = record.fileHash
        let persistedSize = record.fileSize
        let persistedModifiedTime = record.fileModifiedTime
        let currentModifiedTime = await getAssetModificationDate(localIdentifier: record.localFilePath)
        let fileHash = calculateSHA256(for: fileData)
        let sourceChanged = Self.hasSourceChanged(
            persistedHash: persistedHash,
            persistedSize: persistedSize,
            persistedModifiedTime: persistedModifiedTime,
            currentHash: fileHash,
            currentSize: Int64(fileData.count),
            currentModifiedTime: currentModifiedTime
        )

        if sourceChanged {
            // R-32.2: discard the old breakpoint and restart at chunk zero.
            await resetRecordForChangedSource(record: record)
        }

        await updateRecordHash(
            record: record,
            hash: fileHash,
            fileSize: Int64(fileData.count),
            modifiedTime: currentModifiedTime
        )

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
            // Stop requested (auto-backup switched off mid-run): persist progress
            // for 断点续传 and exit without failing the record (R-3.14).
            if stopRequested {
                await saveProgress(record: record, uploadedChunks: Int32(chunkIndex))
                await markRecordPendingForResume(record: record)
                await updateState(.paused)
                await updateProgress(
                    fileName: record.fileName,
                    progress: Double(chunkIndex) / Double(totalChunks),
                    speed: 0,
                    currentChunk: chunkIndex,
                    totalChunks: totalChunks,
                    state: .paused
                )
                return .paused
            }

            // User tapped "暂停" (R-24.2): persist 断点续传 progress and park until
            // the user taps "开始". The queue is preserved (unlike stopAuto), and
            // condition recovery will NOT wake this pause (R-24.5).
            if userPauseRequested {
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

                // Wait for the user to resume. `resume()` clears the flags and
                // resumes this continuation.
                await waitForUserResume()

                await updateState(.uploading)
            }

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
                await markConditionPaused(true)

                // Wait for conditions to restore. `stopAuto()` also wakes this
                // wait, so re-check the stop request before uploading another
                // chunk (R-25.1).
                await waitForConditionRestore()

                if stopRequested {
                    await saveProgress(record: record, uploadedChunks: Int32(chunkIndex))
                    await markRecordPendingForResume(record: record)
                    await markConditionPaused(false)
                    await updateState(.paused)
                    return .paused
                }

                await markConditionPaused(false)
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

    /// Update the merged pause flag / origin for a **condition** pause on the main
    /// actor. A user pause takes priority (R-24.5): while `isUserPaused` is set we
    /// do not overwrite the origin with `.condition`.
    @MainActor
    private func markConditionPaused(_ paused: Bool) {
        isPaused = paused
        if paused {
            if !isUserPaused { pauseOrigin = .condition }
        } else {
            if pauseOrigin == .condition { pauseOrigin = nil }
        }
    }

    /// Park the upload loop until the user taps "开始" (`resume()`).
    ///
    /// Kept deliberately independent from `waitForConditionRestore()` so that
    /// condition recovery (which resumes `pauseContinuation`) can never wake a
    /// user pause — only `resume()` resumes `userPauseContinuation` (R-24.5).
    private func waitForUserResume() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            // Already resumed (race): continue immediately.
            if !userPauseRequested {
                continuation.resume()
                return
            }
            self.userPauseContinuation = continuation
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

    /// Update the record's current source snapshot.
    private func updateRecordHash(
        record: UploadRecord,
        hash: String,
        fileSize: Int64,
        modifiedTime: Date?
    ) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.fileHash = hash
            record.fileSize = fileSize
            record.fileModifiedTime = modifiedTime
            record.updatedAt = Date()
            self.persistenceController.save()
        }
    }

    /// Discard a breakpoint created for source bytes that no longer match.
    private func resetRecordForChangedSource(record: UploadRecord) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.sessionId = nil
            record.uploadedChunks = 0
            record.totalChunks = 0
            record.status = UploadStatus.pending.rawValue
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

    /// Reset a record to `pending` while preserving its `sessionId` and
    /// `uploadedChunks` so the upload can resume from the last chunk boundary
    /// once auto-backup is re-enabled (断点续传, R-3.14).
    private func markRecordPendingForResume(record: UploadRecord) async {
        let context = persistenceController.viewContext
        await context.perform {
            record.status = UploadStatus.pending.rawValue
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
