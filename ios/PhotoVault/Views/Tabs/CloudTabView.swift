import SwiftUI

// MARK: - Cloud Tab View

/// 云端 Tab - 显示已备份到服务器上的文件和目录结构
/// Features: breadcrumb navigation, folder/file list, pull to refresh,
/// image preview, loading indicator, empty state.
struct CloudTabView: View {
    @StateObject private var viewModel = CloudTabViewModel()
    @State private var selectedFile: FileBrowseInfo? = nil
    @State private var showImagePreview: Bool = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // MARK: - Breadcrumb Navigation
                breadcrumbBar

                Divider()

                // MARK: - Content
                if viewModel.isLoading && viewModel.isEmpty {
                    loadingView
                } else if viewModel.isEmpty && viewModel.errorMessage == nil {
                    emptyStateView
                } else if let errorMessage = viewModel.errorMessage {
                    errorView(message: errorMessage)
                } else {
                    directoryListView
                }
            }
            .navigationTitle("云端")
            .navigationBarTitleDisplayMode(.inline)
            .refreshable {
                await viewModel.loadDirectory()
            }
            .fullScreenCover(isPresented: $showImagePreview) {
                if let file = selectedFile {
                    ImagePreviewView(file: file, apiClient: .shared)
                }
            }
            .task {
                await viewModel.loadDirectory()
            }
        }
    }

    // MARK: - Breadcrumb Bar

    private var breadcrumbBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(Array(viewModel.breadcrumbComponents.enumerated()), id: \.offset) { index, component in
                    if index > 0 {
                        Image(systemName: "chevron.right")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }

                    Button {
                        Task {
                            await viewModel.navigateBack(to: component.path)
                        }
                    } label: {
                        Text(component.name)
                            .font(.subheadline)
                            .fontWeight(index == viewModel.breadcrumbComponents.count - 1 ? .semibold : .regular)
                            .foregroundColor(index == viewModel.breadcrumbComponents.count - 1 ? .primary : .blue)
                            .lineLimit(1)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .background(Color(.systemGroupedBackground))
    }

    // MARK: - Loading View

    private var loadingView: some View {
        VStack(spacing: 16) {
            ProgressView()
                .progressViewStyle(CircularProgressViewStyle())
                .scaleEffect(1.2)
            Text("加载中...")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Empty State

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Image(systemName: "cloud")
                .font(.system(size: 48))
                .foregroundColor(.secondary)

            Text("还没有备份文件，去本地 Tab 添加备份文件夹吧")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Error View

    private func errorView(message: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 40))
                .foregroundColor(.orange)

            Text(message)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Button("重试") {
                Task {
                    await viewModel.loadDirectory()
                }
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Directory List

    private var directoryListView: some View {
        List {
            // Directories section
            if !viewModel.directories.isEmpty {
                Section("文件夹") {
                    ForEach(viewModel.directories) { directory in
                        DirectoryRowView(directory: directory)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                Task {
                                    await viewModel.navigateToFolder(name: directory.name)
                                }
                            }
                    }
                }
            }

            // Files section
            if !viewModel.files.isEmpty {
                Section("文件") {
                    ForEach(viewModel.files) { file in
                        FileRowView(
                            file: file,
                            thumbnailURL: viewModel.thumbnailURL(for: file),
                            formattedSize: viewModel.formattedFileSize(file.fileSize),
                            formattedDate: viewModel.formattedDate(file.createdAt)
                        )
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selectedFile = file
                            showImagePreview = true
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .overlay {
            if viewModel.isLoading {
                VStack {
                    HStack {
                        Spacer()
                        ProgressView()
                            .padding(8)
                            .background(Color(.systemBackground).opacity(0.8))
                            .cornerRadius(8)
                            .padding(.trailing, 16)
                            .padding(.top, 8)
                    }
                    Spacer()
                }
            }
        }
    }
}

// MARK: - Directory Row View

/// A row representing a directory/folder in the cloud file list.
struct DirectoryRowView: View {
    let directory: DirectoryInfo

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder.fill")
                .font(.title3)
                .foregroundColor(.blue)
                .frame(width: 36)

            VStack(alignment: .leading, spacing: 4) {
                Text(directory.name)
                    .font(.body)
                    .lineLimit(1)

                Text("\(directory.fileCount) 个文件")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.tertiary)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - File Row View

/// A row representing a file in the cloud file list, with thumbnail and metadata.
struct FileRowView: View {
    let file: FileBrowseInfo
    let thumbnailURL: URL?
    let formattedSize: String
    let formattedDate: String

    var body: some View {
        HStack(spacing: 12) {
            // Thumbnail
            AsyncImage(url: thumbnailURL) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 50, height: 50)
                        .cornerRadius(6)
                        .clipped()
                case .failure:
                    Image(systemName: "photo")
                        .font(.title3)
                        .foregroundColor(.secondary)
                        .frame(width: 50, height: 50)
                        .background(Color(.systemGray5))
                        .cornerRadius(6)
                case .empty:
                    ProgressView()
                        .frame(width: 50, height: 50)
                @unknown default:
                    Color.gray
                        .frame(width: 50, height: 50)
                        .cornerRadius(6)
                }
            }

            // File info
            VStack(alignment: .leading, spacing: 4) {
                Text(file.fileName)
                    .font(.body)
                    .lineLimit(1)

                HStack(spacing: 8) {
                    Text(formattedSize)
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Text(formattedDate)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Spacer()
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Preview

#Preview {
    CloudTabView()
}
