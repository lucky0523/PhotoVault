import SwiftUI

/// 备份任务 Tab - 显示当前备份任务、排队任务和历史备份记录
struct BackupTaskTabView: View {
    @StateObject private var viewModel = BackupTaskViewModel()
    @State private var selectedTab = 0  // 0: 当前任务, 1: 历史记录

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Segmented control
                Picker("", selection: $selectedTab) {
                    Text("当前任务").tag(0)
                    Text("历史记录").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.top, 8)
                .padding(.bottom, 12)

                // Content
                if selectedTab == 0 {
                    CurrentTasksView(viewModel: viewModel)
                } else {
                    HistoryView(viewModel: viewModel)
                }
            }
            .navigationTitle("备份任务")
            .onAppear {
                viewModel.refresh()
            }
        }
    }
}

// MARK: - Current Tasks View

/// Shows the currently uploading file, queued files, and pause status
private struct CurrentTasksView: View {
    @ObservedObject var viewModel: BackupTaskViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Pause state banner
                if viewModel.isPaused, let reason = viewModel.pauseReason {
                    PauseBannerView(reason: reason)
                }

                // Currently uploading file
                if let progress = viewModel.currentProgress {
                    CurrentUploadCard(progress: progress, viewModel: viewModel)
                }

                // Pending queue
                if !viewModel.pendingQueue.isEmpty {
                    PendingQueueSection(
                        records: viewModel.pendingQueue,
                        viewModel: viewModel
                    )
                }

                // Empty state
                if viewModel.currentProgress == nil && viewModel.pendingQueue.isEmpty && !viewModel.isPaused {
                    EmptyCurrentTaskView()
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 16)
        }
    }
}

// MARK: - Pause Banner

/// Displays pause reason and resume condition hint
private struct PauseBannerView: View {
    let reason: PauseReason

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "pause.circle.fill")
                    .foregroundStyle(.yellow)
                    .font(.title3)
                Text("备份已暂停")
                    .font(.headline)
                Spacer()
            }

            Text("原因：\(reason.rawValue)")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Text(reason.resumeHint)
                .font(.caption)
                .foregroundStyle(.blue)
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.yellow.opacity(0.1))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.yellow.opacity(0.3), lineWidth: 1)
        )
    }
}

// MARK: - Current Upload Card

/// Shows the file currently being uploaded with progress, speed, and remaining time
private struct CurrentUploadCard: View {
    let progress: UploadProgressInfo
    @ObservedObject var viewModel: BackupTaskViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // Header
            HStack {
                Image(systemName: "arrow.up.circle.fill")
                    .foregroundStyle(.blue)
                    .font(.title3)
                Text("正在上传")
                    .font(.headline)
                Spacer()
                stateLabel
            }

            // File name
            Text(progress.fileName)
                .font(.subheadline)
                .lineLimit(1)
                .truncationMode(.middle)

            // Progress bar
            ProgressView(value: progress.progress)
                .tint(.blue)

            // Speed and remaining time
            HStack {
                Text(viewModel.formatSpeed(progress.uploadSpeed))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()

                Text("\(Int(progress.progress * 100))%")
                    .font(.caption)
                    .fontWeight(.medium)

                Spacer()

                Text("剩余 \(viewModel.estimateRemainingTime(progress: progress.progress, speed: progress.uploadSpeed, fileSize: Int64(Double(ChunkUploader.chunkSize) * Double(progress.totalChunks))))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // Chunk progress detail
            Text("分块 \(progress.currentChunk)/\(progress.totalChunks)")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.systemBackground))
        )
        .shadow(color: .black.opacity(0.05), radius: 4, y: 2)
    }

    @ViewBuilder
    private var stateLabel: some View {
        switch progress.state {
        case .hashing:
            Label("计算哈希", systemImage: "number.circle")
                .font(.caption)
                .foregroundStyle(.orange)
        case .checkingDuplicate:
            Label("检查重复", systemImage: "doc.on.doc")
                .font(.caption)
                .foregroundStyle(.purple)
        case .initializing:
            Label("初始化", systemImage: "gear")
                .font(.caption)
                .foregroundStyle(.gray)
        case .uploading:
            Label("上传中", systemImage: "arrow.up")
                .font(.caption)
                .foregroundStyle(.blue)
        case .completing:
            Label("验证中", systemImage: "checkmark.shield")
                .font(.caption)
                .foregroundStyle(.green)
        case .paused:
            Label("已暂停", systemImage: "pause.fill")
                .font(.caption)
                .foregroundStyle(.yellow)
        default:
            EmptyView()
        }
    }
}

// MARK: - Pending Queue Section

/// Displays the list of files waiting in the upload queue
private struct PendingQueueSection: View {
    let records: [UploadRecord]
    @ObservedObject var viewModel: BackupTaskViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "clock.fill")
                    .foregroundStyle(.orange)
                Text("排队中")
                    .font(.headline)
                Spacer()
                Text("\(records.count) 个文件")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            ForEach(records, id: \.id) { record in
                PendingFileRow(record: record, viewModel: viewModel)
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.systemBackground))
        )
        .shadow(color: .black.opacity(0.05), radius: 4, y: 2)
    }
}

/// A single row in the pending queue
private struct PendingFileRow: View {
    let record: UploadRecord
    @ObservedObject var viewModel: BackupTaskViewModel

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "doc.fill")
                .foregroundStyle(.gray)
                .font(.caption)

            VStack(alignment: .leading, spacing: 2) {
                Text(record.fileName)
                    .font(.subheadline)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Text(viewModel.formatFileSize(record.fileSize))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Text("等待中")
                .font(.caption)
                .foregroundStyle(.orange)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(
                    Capsule()
                        .fill(Color.orange.opacity(0.1))
                )
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Empty State

/// Shown when there are no current tasks
private struct EmptyCurrentTaskView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.circle")
                .font(.system(size: 48))
                .foregroundStyle(.green.opacity(0.6))

            Text("当前没有备份任务")
                .font(.headline)
                .foregroundStyle(.secondary)

            Text("新的照片会自动加入备份队列")
                .font(.subheadline)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }
}

// MARK: - History View

/// Shows backup history grouped by date with status filtering
private struct HistoryView: View {
    @ObservedObject var viewModel: BackupTaskViewModel

    var body: some View {
        VStack(spacing: 0) {
            // Status filter
            Picker("", selection: $viewModel.historyFilter) {
                ForEach(HistoryFilter.allCases, id: \.self) { filter in
                    Text(filter.rawValue).tag(filter)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.bottom, 12)

            // History list
            if viewModel.groupedHistory.isEmpty {
                EmptyHistoryView()
            } else {
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(viewModel.groupedHistory) { group in
                            HistoryDateSection(group: group, viewModel: viewModel)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 16)
                }
            }
        }
    }
}

// MARK: - History Date Section

/// A section of history records for a specific date
private struct HistoryDateSection: View {
    let group: HistoryDateGroup
    @ObservedObject var viewModel: BackupTaskViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Date header
            Text(group.dateLabel)
                .font(.subheadline)
                .fontWeight(.semibold)
                .foregroundStyle(.secondary)
                .padding(.leading, 4)

            // Records
            VStack(spacing: 0) {
                ForEach(group.records, id: \.id) { record in
                    HistoryRecordRow(record: record, viewModel: viewModel)

                    if record.id != group.records.last?.id {
                        Divider()
                            .padding(.leading, 40)
                    }
                }
            }
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.systemBackground))
            )
            .shadow(color: .black.opacity(0.05), radius: 4, y: 2)
        }
    }
}

// MARK: - History Record Row

/// A single history record showing file info, time, and status
private struct HistoryRecordRow: View {
    let record: BackupHistory
    @ObservedObject var viewModel: BackupTaskViewModel

    var body: some View {
        HStack(spacing: 12) {
            // Status icon
            statusIcon

            // File info
            VStack(alignment: .leading, spacing: 3) {
                Text(record.fileName)
                    .font(.subheadline)
                    .lineLimit(1)
                    .truncationMode(.middle)

                HStack(spacing: 8) {
                    Text(viewModel.formatFileSize(record.fileSize))
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Text(formatTime(record.completedAt))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            // Status badge or retry button
            if record.resultStatus == .failed {
                Button {
                    viewModel.retryFailed(record: record)
                } label: {
                    Text("重试")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(
                            Capsule()
                                .fill(Color.red)
                        )
                }
            } else {
                statusBadge
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch record.resultStatus {
        case .success:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .font(.title3)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundStyle(.red)
                .font(.title3)
        case .skipped:
            Image(systemName: "arrow.right.circle.fill")
                .foregroundStyle(.gray)
                .font(.title3)
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        switch record.resultStatus {
        case .success:
            Text("成功")
                .font(.caption)
                .foregroundStyle(.green)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(
                    Capsule()
                        .fill(Color.green.opacity(0.1))
                )
        case .skipped:
            Text("跳过")
                .font(.caption)
                .foregroundStyle(.gray)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(
                    Capsule()
                        .fill(Color.gray.opacity(0.1))
                )
        case .failed:
            EmptyView()
        }
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}

// MARK: - Empty History View

/// Shown when there are no history records
private struct EmptyHistoryView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 48))
                .foregroundStyle(.gray.opacity(0.5))

            Text("暂无备份记录")
                .font(.headline)
                .foregroundStyle(.secondary)

            Text("完成备份后记录会显示在这里")
                .font(.subheadline)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.vertical, 60)
    }
}

// MARK: - Preview

#Preview {
    BackupTaskTabView()
}
