import SwiftUI

// MARK: - Local Tab View

/// 本地 Tab - 显示手机本地的图片文件夹列表及其备份状态
/// Features: folder cards with progress, pull to refresh, FAB to add folder,
/// long press context menu, empty state, and storage policy sheet.
struct LocalTabView: View {
    @StateObject private var viewModel = LocalTabViewModel()
    @State private var showFolderPicker: Bool = false
    @State private var selectedFolderForPolicy: BackupFolder? = nil
    @State private var showPolicySheet: Bool = false

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottomTrailing) {
                // MARK: - Content
                if viewModel.folders.isEmpty {
                    emptyStateView
                } else {
                    folderListView
                }

                // MARK: - FAB
                addFolderFAB
            }
            .navigationTitle("本地")
            .refreshable {
                await viewModel.refresh()
            }
            .sheet(isPresented: $showPolicySheet) {
                if let folder = selectedFolderForPolicy {
                    StoragePolicySheet(folder: folder) { useCustomPath, customPath, useYearMonthLayer in
                        viewModel.updateStoragePolicy(
                            for: folder,
                            useCustomPath: useCustomPath,
                            customPath: customPath,
                            useYearMonthLayer: useYearMonthLayer
                        )
                    }
                    .presentationDetents([.medium, .large])
                }
            }
        }
    }

    // MARK: - Empty State

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Image(systemName: "folder.badge.plus")
                .font(.system(size: 48))
                .foregroundColor(.secondary)

            Text("还没有备份文件夹")
                .font(.headline)
                .foregroundColor(.primary)

            Text("点击右下角按钮添加要备份的文件夹")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }

    // MARK: - Folder List

    private var folderListView: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(viewModel.folders, id: \.id) { folder in
                    FolderCardView(
                        folder: folder,
                        status: viewModel.backupStatus(for: folder),
                        progressPercentage: viewModel.progressPercentage(for: folder)
                    )
                    .contextMenu {
                        Button {
                            selectedFolderForPolicy = folder
                            showPolicySheet = true
                        } label: {
                            Label("配置策略", systemImage: "gearshape")
                        }

                        Button(role: .destructive) {
                            viewModel.removeFolder(folder)
                        } label: {
                            Label("移除文件夹", systemImage: "trash")
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 80) // Extra padding for FAB
        }
    }

    // MARK: - FAB Button

    private var addFolderFAB: some View {
        Button {
            showFolderPicker = true
            // In a real app this would open a document/folder picker.
            // For now we add a sample folder to demonstrate.
            addSampleFolder()
        } label: {
            Image(systemName: "plus")
                .font(.title2)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .frame(width: 56, height: 56)
                .background(Color.blue)
                .clipShape(Circle())
                .shadow(color: Color.black.opacity(0.2), radius: 4, x: 0, y: 2)
        }
        .padding(.trailing, 20)
        .padding(.bottom, 20)
    }

    // MARK: - Helpers

    /// Adds a sample folder for demonstration (replace with folder picker in production)
    private func addSampleFolder() {
        let sampleFolders = [
            ("DCIM/Camera", "/storage/DCIM/Camera"),
            ("Screenshots", "/storage/Pictures/Screenshots"),
            ("Downloads", "/storage/Download"),
            ("WhatsApp Images", "/storage/WhatsApp/Media/Images")
        ]

        // Add a folder that isn't already added
        let existingPaths = Set(viewModel.folders.map { $0.folderPath })
        if let newFolder = sampleFolders.first(where: { !existingPaths.contains($0.1) }) {
            viewModel.addFolder(path: newFolder.1, name: newFolder.0)
            // Open policy sheet for the newly added folder
            if let folder = viewModel.folders.first(where: { $0.folderPath == newFolder.1 }) {
                selectedFolderForPolicy = folder
                showPolicySheet = true
            }
        }
    }
}

// MARK: - Folder Card View

/// A card representing a backup folder with name, file counts, progress, and status icon.
struct FolderCardView: View {
    let folder: BackupFolder
    let status: FolderBackupStatus
    let progressPercentage: Int

    var body: some View {
        HStack(spacing: 12) {
            // MARK: - Folder Icon
            Image(systemName: "folder.fill")
                .font(.title2)
                .foregroundColor(.blue)
                .frame(width: 40)

            // MARK: - Info
            VStack(alignment: .leading, spacing: 4) {
                Text(folder.folderName)
                    .font(.headline)
                    .lineLimit(1)

                HStack(spacing: 12) {
                    Label("\(folder.totalFiles) 张", systemImage: "photo")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Label("\(folder.backedUpFiles) 已备份", systemImage: "checkmark.circle")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                // Progress bar
                ProgressView(value: folder.backupProgress)
                    .progressViewStyle(LinearProgressViewStyle(tint: progressColor))

                Text("\(progressPercentage)%")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()

            // MARK: - Status Icon
            VStack {
                Image(systemName: status.icon)
                    .font(.title3)
                    .foregroundColor(statusColor)

                Text(status.label)
                    .font(.caption2)
                    .foregroundColor(statusColor)
            }
            .frame(width: 50)
        }
        .padding(14)
        .background(Color(.systemBackground))
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.06), radius: 4, x: 0, y: 2)
    }

    private var statusColor: Color {
        switch status {
        case .allBackedUp: return .green
        case .backingUp: return .blue
        case .pending: return .orange
        }
    }

    private var progressColor: Color {
        switch status {
        case .allBackedUp: return .green
        case .backingUp: return .blue
        case .pending: return .orange
        }
    }
}

// MARK: - Preview

#Preview {
    LocalTabView()
        .environment(\.managedObjectContext, PersistenceController(inMemory: true).viewContext)
}
