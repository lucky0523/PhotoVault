import XCTest
import CoreData
@testable import PhotoVault

/// Tests for source tracking and the "自动备份" toggle-off behavior (R-3.13/3.14/3.15).
///
/// Tests run in the `PhotoVaultTests` unit-test target with an in-memory Core Data store.
@MainActor
final class AutoBackupToggleTests: XCTestCase {

    private var persistence: PersistenceController!

    override func setUp() {
        super.setUp()
        // Fresh in-memory Core Data stack per test.
        persistence = PersistenceController(inMemory: true)
        // Start each test from the default (auto-backup on).
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
        source: BackupSource
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
        persistence.save()
        return record
    }

    private func fetchAll() -> [UploadRecord] {
        (try? persistence.viewContext.fetch(UploadRecord.fetchRequest())) ?? []
    }

    // MARK: - Source tracking (R-3.13)

    func testDefaultSourceIsAutoForLegacyRecords() {
        let record = makeRecord(name: "legacy.jpg", source: .auto)
        record.source = nil // simulate a record created before source tracking
        persistence.save()
        XCTAssertEqual(record.uploadSource, .auto)
    }

    func testManualSourceIsPreserved() {
        let record = makeRecord(name: "manual.jpg", source: .manual)
        XCTAssertEqual(record.uploadSource, .manual)
    }

    // MARK: - Toggle off with an automatic task (R-3.14)

    func testTurningOffAutoBackupClearsQueuedAutomaticFiles() {
        makeRecord(name: "a1.jpg", source: .auto)
        makeRecord(name: "a2.jpg", source: .auto)

        let uploader = ChunkUploader(persistenceController: persistence)
        let vm = SettingsViewModel(
            persistenceController: persistence,
            chunkUploader: uploader
        )

        vm.autoBackupEnabled = false

        let remaining = fetchAll()
        XCTAssertTrue(remaining.isEmpty, "queued automatic files should be cleared")
    }

    // MARK: - Toggle off preserves manual files (R-3.15)

    func testTurningOffAutoBackupPreservesManualQueuedFiles() {
        makeRecord(name: "auto.jpg", source: .auto)
        makeRecord(name: "manual.jpg", source: .manual)

        let uploader = ChunkUploader(persistenceController: persistence)
        let vm = SettingsViewModel(
            persistenceController: persistence,
            chunkUploader: uploader
        )

        vm.autoBackupEnabled = false

        let remaining = fetchAll()
        XCTAssertEqual(remaining.count, 1)
        XCTAssertEqual(remaining.first?.uploadSource, .manual,
                       "manual queued files must be preserved")
    }

    // MARK: - Persistence

    func testAutoBackupPreferencePersists() {
        let uploader = ChunkUploader(persistenceController: persistence)
        let vm = SettingsViewModel(
            persistenceController: persistence,
            chunkUploader: uploader
        )
        vm.autoBackupEnabled = false
        XCTAssertFalse(BackupPreferences.autoBackupEnabled)
        vm.autoBackupEnabled = true
        XCTAssertTrue(BackupPreferences.autoBackupEnabled)
    }

    // MARK: - stopAuto only affects automatic runs

    func testStopAutoIgnoredWhenNotRunning() {
        let uploader = ChunkUploader(persistenceController: persistence)
        // Not running: stopAuto must be a no-op and must not crash.
        uploader.stopAuto()
        XCTAssertFalse(uploader.isRunning)
    }
}
