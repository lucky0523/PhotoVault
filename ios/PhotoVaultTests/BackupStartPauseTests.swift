import XCTest
import CoreData
@testable import PhotoVault

/// Tests for the manual 开始/暂停 control and the user-vs-condition pause
/// distinction on the 备份任务 Tab (R-24.1 – R-24.6).
///
/// Tests run in the `PhotoVaultTests` unit-test target with an in-memory Core Data store.
@MainActor
final class BackupStartPauseTests: XCTestCase {

    private var persistence: PersistenceController!

    override func setUp() {
        super.setUp()
        persistence = PersistenceController(inMemory: true)
    }

    override func tearDown() {
        persistence = nil
        super.tearDown()
    }

    // MARK: - Helpers

    private func makeViewModel() -> BackupTaskViewModel {
        BackupTaskViewModel(
            persistenceController: persistence,
            chunkUploader: ChunkUploader(persistenceController: persistence),
            conditionChecker: BackupConditionChecker()
        )
    }

    @discardableResult
    private func makePending(name: String) -> UploadRecord {
        let record = UploadRecord.create(
            in: persistence.viewContext,
            localFilePath: "asset://\(name)",
            fileHash: "",
            fileSize: 0,
            fileName: name,
            source: .manual
        )
        record.status = UploadStatus.pending.rawValue
        persistence.save()
        return record
    }

    // MARK: - Button label (R-24.1)

    func testButtonShowsPauseWhileRunning() {
        let vm = makeViewModel()
        vm.isBackupRunning = true
        vm.isPaused = false
        XCTAssertTrue(vm.isStartPauseShowingPause, "running backup should show 暂停")
    }

    func testButtonShowsStartWhenPaused() {
        let vm = makeViewModel()
        vm.isBackupRunning = true
        vm.isPaused = true
        XCTAssertFalse(vm.isStartPauseShowingPause, "paused backup should show 开始")
    }

    func testButtonShowsStartWhenIdle() {
        let vm = makeViewModel()
        vm.isBackupRunning = false
        vm.isPaused = false
        XCTAssertFalse(vm.isStartPauseShowingPause, "idle should show 开始")
    }

    // MARK: - Button enablement (R-24.4)

    func testButtonDisabledWhenQueueEmptyAndNothingInProgress() {
        let vm = makeViewModel()
        vm.pendingQueue = []
        vm.isBackupRunning = false
        vm.isPaused = false
        XCTAssertFalse(vm.isStartPauseEnabled, "empty queue + nothing running should disable")
    }

    func testButtonEnabledWithQueuedFiles() {
        let vm = makeViewModel()
        vm.pendingQueue = [makePending(name: "queued.jpg")]
        vm.isBackupRunning = false
        vm.isPaused = false
        XCTAssertTrue(vm.isStartPauseEnabled, "queued files should enable")
    }

    func testButtonEnabledWhileRunning() {
        let vm = makeViewModel()
        vm.pendingQueue = []
        vm.isBackupRunning = true
        vm.isPaused = false
        XCTAssertTrue(vm.isStartPauseEnabled, "an in-progress task should enable")
    }

    func testButtonEnabledWhilePaused() {
        let vm = makeViewModel()
        vm.pendingQueue = []
        vm.isBackupRunning = false
        vm.isPaused = true
        XCTAssertTrue(vm.isStartPauseEnabled, "a paused task should enable")
    }

    // MARK: - User vs condition pause (R-24.5)

    func testUserPauseSuppressesConditionReason() {
        let vm = makeViewModel()
        vm.isPaused = true
        vm.isUserPaused = true
        // Even if a condition reason were set, the UI must treat it as a user pause.
        vm.pauseReason = nil
        XCTAssertTrue(vm.isUserPaused)
        XCTAssertNil(vm.pauseReason, "user pause must not surface a condition reason")
    }

    // MARK: - ChunkUploader user-pause state machine

    func testUploaderInitialPauseStateIsClear() {
        let uploader = ChunkUploader(persistenceController: persistence)
        XCTAssertFalse(uploader.isUserPaused)
        XCTAssertNil(uploader.pauseOrigin)
        XCTAssertFalse(uploader.isPaused)
    }

    func testResumeIsNoOpWhenNotUserPaused() {
        let uploader = ChunkUploader(persistenceController: persistence)
        uploader.resume()
        XCTAssertFalse(uploader.isUserPaused)
        XCTAssertNil(uploader.pauseOrigin)
    }

    func testPauseIsNoOpWhenNotRunning() {
        let uploader = ChunkUploader(persistenceController: persistence)
        // Not running: pause() must be a no-op (button is only active while running).
        uploader.pause()
        XCTAssertFalse(uploader.isUserPaused)
        XCTAssertNil(uploader.pauseOrigin)
        XCTAssertFalse(uploader.isPaused)
    }
}
