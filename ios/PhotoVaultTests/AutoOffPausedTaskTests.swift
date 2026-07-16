import XCTest
import CoreData
@testable import PhotoVault

/// Tests for the "关闭自动备份后保留正在上传文件为已暂停任务" alignment on iOS
/// (mirrors Android task 27, requirements 25-32).
///
/// Tests run in the `PhotoVaultTests` unit-test target with an in-memory Core Data store.
/// Paths requiring a real photo library are covered through an injected source
/// availability check; end-to-end PHAsset reading still requires simulator/device validation.
@MainActor
final class AutoOffPausedTaskTests: XCTestCase {

    private var persistence: PersistenceController!

    override func setUp() {
        super.setUp()
        persistence = PersistenceController(inMemory: true)
        BackupPreferences.autoBackupEnabled = true
    }

    override func tearDown() {
        persistence = nil
        super.tearDown()
    }

    // MARK: - Helpers

    @discardableResult
    private func makeRecord(
        name: String,
        status: UploadStatus = .pending,
        source: BackupSource = .auto,
        uploadedChunks: Int32 = 0,
        totalChunks: Int32 = 0,
        sessionId: String? = nil,
        createdAt: Date = Date(),
        pauseSource: PauseSource? = nil,
        pausedAt: Date? = nil
    ) -> UploadRecord {
        let record = UploadRecord.create(
            in: persistence.viewContext,
            localFilePath: "asset://\(name)",
            fileHash: "",
            fileSize: 0,
            fileName: name,
            source: source
        )
        record.status = status.rawValue
        record.uploadedChunks = uploadedChunks
        record.totalChunks = totalChunks
        record.sessionId = sessionId
        record.createdAt = createdAt
        record.pauseSource = pauseSource?.rawValue
        record.pausedAt = pausedAt
        persistence.save()
        return record
    }

    // MARK: - Property 20: progress computation (R-26.2/26.3)

    func testComputeProgressPercent() {
        XCTAssertEqual(BackupTaskViewModel.computeProgressPercent(uploadedChunks: 0, totalChunks: 0), 0)
        XCTAssertEqual(BackupTaskViewModel.computeProgressPercent(uploadedChunks: 5, totalChunks: 0), 0)
        XCTAssertEqual(BackupTaskViewModel.computeProgressPercent(uploadedChunks: 1, totalChunks: 4), 25)
        XCTAssertEqual(BackupTaskViewModel.computeProgressPercent(uploadedChunks: 3, totalChunks: 4), 75)
        XCTAssertEqual(BackupTaskViewModel.computeProgressPercent(uploadedChunks: 4, totalChunks: 4), 100)
        // Clamps out-of-range inputs.
        XCTAssertEqual(BackupTaskViewModel.computeProgressPercent(uploadedChunks: 9, totalChunks: 4), 100)
        XCTAssertEqual(BackupTaskViewModel.computeProgressPercent(uploadedChunks: -2, totalChunks: 4), 0)
    }

    // MARK: - Property 18: partition of pending records on toggle-off (R-25.1/25.2/25.3)

    func testPartitionOnAutoOffSeparatesInFlightFromNotStarted() {
        let inFlight = SettingsViewModel.PendingRecordSnapshot(id: UUID(), isInFlight: true, isCurrent: false)
        let current = SettingsViewModel.PendingRecordSnapshot(id: UUID(), isInFlight: false, isCurrent: true)
        let notStarted = SettingsViewModel.PendingRecordSnapshot(id: UUID(), isInFlight: false, isCurrent: false)

        let result = SettingsViewModel.partitionOnAutoOff([inFlight, current, notStarted])

        XCTAssertEqual(Set(result.toMark), Set([inFlight.id, current.id]))
        XCTAssertEqual(result.toClear, [notStarted.id])
    }

    // MARK: - R-25.5: no in-flight file -> everything cleared, nothing marked

    func testPartitionOnAutoOffWithNoInFlightClearsAll() {
        let a = SettingsViewModel.PendingRecordSnapshot(id: UUID(), isInFlight: false, isCurrent: false)
        let b = SettingsViewModel.PendingRecordSnapshot(id: UUID(), isInFlight: false, isCurrent: false)

        let result = SettingsViewModel.partitionOnAutoOff([a, b])

        XCTAssertTrue(result.toMark.isEmpty)
        XCTAssertEqual(Set(result.toClear), Set([a.id, b.id]))
    }

    // MARK: - Toggle off marks in-flight AUTO_OFF and clears not-started (R-25.1/25.2/25.3)

    func testTurningOffAutoBackupMarksInFlightAndClearsNotStarted() {
        // In-flight: has uploaded chunks + session.
        makeRecord(name: "inflight.jpg", uploadedChunks: 2, totalChunks: 5, sessionId: "sess-1")
        // Not started: no progress.
        makeRecord(name: "queued.jpg")

        let uploader = ChunkUploader(persistenceController: persistence)
        let vm = SettingsViewModel(persistenceController: persistence, chunkUploader: uploader)

        vm.autoBackupEnabled = false

        let all = (try? persistence.viewContext.fetch(UploadRecord.fetchRequest())) ?? []
        XCTAssertEqual(all.count, 1, "the not-started queued file should be cleared")
        let kept = all.first
        XCTAssertEqual(kept?.fileName, "inflight.jpg")
        XCTAssertTrue(kept?.isAutoOffPaused == true, "in-flight file must be marked AUTO_OFF")
        XCTAssertNotNil(kept?.pausedAt)
    }

    // MARK: - R-29.1: manual in-flight run untouched

    func testTurningOffAutoBackupPreservesManualQueuedFiles() {
        makeRecord(name: "manual.jpg", source: .manual)

        let uploader = ChunkUploader(persistenceController: persistence)
        let vm = SettingsViewModel(persistenceController: persistence, chunkUploader: uploader)

        vm.autoBackupEnabled = false

        let all = (try? persistence.viewContext.fetch(UploadRecord.fetchRequest())) ?? []
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all.first?.uploadSource, .manual)
        XCTAssertFalse(all.first?.isAutoOffPaused == true)
    }

    // MARK: - Property 19: AUTO_OFF excluded from pending fetch (R-25.4/30.3)

    func testPendingFetchExcludesAutoOff() {
        makeRecord(name: "normal.jpg")
        makeRecord(name: "paused.jpg", uploadedChunks: 1, totalChunks: 3,
                   sessionId: "s", pauseSource: .autoOff, pausedAt: Date())

        let pending = (try? persistence.viewContext.fetch(UploadRecord.pendingFetchRequest())) ?? []
        XCTAssertEqual(pending.map { $0.fileName }, ["normal.jpg"])
    }

    // MARK: - Property 21: paused list ordered newest-pause-first (R-26.1)

    func testLoadPausedTasksOrdersByPausedAtDescending() {
        let old = Date().addingTimeInterval(-3600)
        let recent = Date()
        makeRecord(name: "old.jpg", uploadedChunks: 1, totalChunks: 2,
                   sessionId: "s1", pauseSource: .autoOff, pausedAt: old)
        makeRecord(name: "recent.jpg", uploadedChunks: 1, totalChunks: 2,
                   sessionId: "s2", pauseSource: .autoOff, pausedAt: recent)

        let vm = BackupTaskViewModel(
            persistenceController: persistence,
            chunkUploader: ChunkUploader(persistenceController: persistence)
        )
        vm.loadPausedTasks()

        XCTAssertEqual(vm.pausedTasks.map { $0.fileName }, ["recent.jpg", "old.jpg"])
        XCTAssertFalse(vm.pausedTasksLoadError)
    }

    // MARK: - Property 22: expired records filtered from paused list (R-32.1)

    func testLoadPausedTasksFiltersExpiredRecords() {
        let expiredCreated = Date().addingTimeInterval(-8 * 24 * 60 * 60) // 8 days ago
        makeRecord(name: "expired.jpg", uploadedChunks: 1, totalChunks: 2, sessionId: "s",
                   createdAt: expiredCreated, pauseSource: .autoOff, pausedAt: Date())
        makeRecord(name: "valid.jpg", uploadedChunks: 1, totalChunks: 2, sessionId: "s2",
                   createdAt: Date(), pauseSource: .autoOff, pausedAt: Date())

        let vm = BackupTaskViewModel(
            persistenceController: persistence,
            chunkUploader: ChunkUploader(persistenceController: persistence)
        )
        vm.loadPausedTasks()

        XCTAssertEqual(vm.pausedTasks.map { $0.fileName }, ["valid.jpg"])
        let persisted = (try? persistence.viewContext.fetch(UploadRecord.fetchRequest())) ?? []
        XCTAssertEqual(persisted.map { $0.fileName }, ["valid.jpg"],
                       "expired AUTO_OFF records should be removed from persistence")
    }

    // MARK: - R-32.2: changed source invalidates the old breakpoint

    func testChangedSourceSnapshotRestartsResume() {
        let date = Date()
        XCTAssertFalse(ChunkUploader.hasSourceChanged(
            persistedHash: "abc", persistedSize: 100, persistedModifiedTime: date,
            currentHash: "abc", currentSize: 100, currentModifiedTime: date
        ))
        XCTAssertTrue(ChunkUploader.hasSourceChanged(
            persistedHash: "abc", persistedSize: 100, persistedModifiedTime: date,
            currentHash: "def", currentSize: 100, currentModifiedTime: date
        ))
        XCTAssertTrue(ChunkUploader.hasSourceChanged(
            persistedHash: "abc", persistedSize: 100, persistedModifiedTime: date,
            currentHash: "abc", currentSize: 101, currentModifiedTime: date
        ))
        XCTAssertTrue(ChunkUploader.hasSourceChanged(
            persistedHash: "abc", persistedSize: 100, persistedModifiedTime: date,
            currentHash: "abc", currentSize: 100, currentModifiedTime: date.addingTimeInterval(1)
        ))
    }

    /// Property-style coverage: any known size mismatch invalidates resume.
    /// **Validates: Requirements 32.2**
    func testAnyKnownSizeMismatchInvalidatesResume() {
        for size in 1...100 {
            XCTAssertTrue(ChunkUploader.hasSourceChanged(
                persistedHash: "", persistedSize: Int64(size), persistedModifiedTime: nil,
                currentHash: "", currentSize: Int64(size + 1), currentModifiedTime: nil
            ))
        }
    }

    // MARK: - R-27.1/27.2/30.5: continue is per-item and remains manual

    func testResumeClearsOnlySelectedAutoOffMarkerWithoutEnablingAutoBackup() {
        BackupPreferences.autoBackupEnabled = false
        let selected = makeRecord(name: "selected.jpg", uploadedChunks: 1, totalChunks: 2,
                                  sessionId: "s1", pauseSource: .autoOff, pausedAt: Date())
        let other = makeRecord(name: "other.jpg", uploadedChunks: 1, totalChunks: 2,
                               sessionId: "s2", pauseSource: .autoOff, pausedAt: Date())
        let vm = BackupTaskViewModel(
            persistenceController: persistence,
            chunkUploader: ChunkUploader(persistenceController: persistence),
            sourceExists: { _ in true }
        )

        vm.resumePausedTask(fileURI: selected.localFilePath)

        XCTAssertFalse(BackupPreferences.autoBackupEnabled)
        XCTAssertFalse(selected.isAutoOffPaused)
        XCTAssertEqual(selected.uploadSource, .manual)
        XCTAssertTrue(other.isAutoOffPaused)
    }

    // MARK: - R-27.6/32.3: missing source removes the stale record

    func testResumeMissingSourceDeletesRecordAndShowsError() {
        let record = makeRecord(name: "missing.jpg", uploadedChunks: 1, totalChunks: 2,
                                sessionId: "s", pauseSource: .autoOff, pausedAt: Date())
        let vm = BackupTaskViewModel(
            persistenceController: persistence,
            chunkUploader: ChunkUploader(persistenceController: persistence),
            sourceExists: { _ in false }
        )

        vm.resumePausedTask(fileURI: record.localFilePath)

        XCTAssertEqual(vm.pausedTaskActionError, "源文件已不存在，无法续传")
        let remaining = (try? persistence.viewContext.fetch(UploadRecord.fetchRequest())) ?? []
        XCTAssertTrue(remaining.isEmpty)
    }

    // MARK: - R-28.2/28.4: clear removes the record and the entry

    func testClearPausedTaskDeletesRecordAndEntry() {
        let record = makeRecord(name: "clear.jpg", uploadedChunks: 1, totalChunks: 2,
                                sessionId: "s", pauseSource: .autoOff, pausedAt: Date())
        let uri = record.localFilePath

        let vm = BackupTaskViewModel(
            persistenceController: persistence,
            chunkUploader: ChunkUploader(persistenceController: persistence)
        )
        vm.loadPausedTasks()
        XCTAssertEqual(vm.pausedTasks.count, 1)

        vm.clearPausedTask(fileURI: uri)

        XCTAssertTrue(vm.pausedTasks.isEmpty)
        let all = (try? persistence.viewContext.fetch(UploadRecord.fetchRequest())) ?? []
        XCTAssertTrue(all.isEmpty)
    }

    // MARK: - R-31.1: rebuilt from persistence on a fresh view model (restart sim)

    func testPausedTasksRebuiltFromPersistence() {
        makeRecord(name: "persisted.jpg", uploadedChunks: 1, totalChunks: 4,
                   sessionId: "s", pauseSource: .autoOff, pausedAt: Date())

        // A brand-new view model simulates an app relaunch reading the store.
        let vm = BackupTaskViewModel(
            persistenceController: persistence,
            chunkUploader: ChunkUploader(persistenceController: persistence)
        )

        XCTAssertEqual(vm.pausedTasks.map { $0.fileName }, ["persisted.jpg"])
        XCTAssertEqual(vm.pausedTasks.first?.progressPercent, 25)
    }

    // MARK: - markAutoOffPaused / clearAutoOffPause round-trip (R-25.2/27.1)

    func testMarkAndClearAutoOffPause() {
        let record = makeRecord(name: "toggle.jpg", uploadedChunks: 1, totalChunks: 2, sessionId: "s")
        XCTAssertFalse(record.isAutoOffPaused)

        record.markAutoOffPaused()
        XCTAssertTrue(record.isAutoOffPaused)
        XCTAssertEqual(record.uploadStatus, .pending)
        XCTAssertNotNil(record.pausedAt)

        record.clearAutoOffPause()
        XCTAssertFalse(record.isAutoOffPaused)
        XCTAssertNil(record.pausedAt)
    }
}
