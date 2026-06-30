import SwiftUI

// MARK: - Image Preview View

/// Full screen image preview with zoom (MagnificationGesture),
/// download original button, and close/dismiss button.
/// Loads medium thumbnail first, then the full resolution image.
struct ImagePreviewView: View {
    let file: FileBrowseInfo
    let apiClient: APIClient

    @Environment(\.dismiss) private var dismiss

    @State private var thumbnailImage: UIImage? = nil
    @State private var fullImage: UIImage? = nil
    @State private var isLoadingFull: Bool = false
    @State private var isDownloading: Bool = false
    @State private var downloadComplete: Bool = false
    @State private var errorMessage: String? = nil
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0

    /// The displayed image: prefer full image, fallback to thumbnail
    private var displayImage: UIImage? {
        fullImage ?? thumbnailImage
    }

    var body: some View {
        ZStack {
            // Background
            Color.black.ignoresSafeArea()

            // Image content
            if let image = displayImage {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .scaleEffect(scale)
                    .gesture(magnificationGesture)
                    .onTapGesture(count: 2) {
                        withAnimation(.easeInOut(duration: 0.3)) {
                            if scale > 1.0 {
                                scale = 1.0
                                lastScale = 1.0
                            } else {
                                scale = 2.5
                                lastScale = 2.5
                            }
                        }
                    }
            } else if errorMessage != nil {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle)
                        .foregroundColor(.orange)
                    Text(errorMessage ?? "加载失败")
                        .foregroundColor(.white)
                        .font(.subheadline)
                }
            } else {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    .scaleEffect(1.5)
            }

            // Loading indicator for full image
            if isLoadingFull && thumbnailImage != nil {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            .padding(8)
                            .background(Color.black.opacity(0.5))
                            .cornerRadius(8)
                            .padding(.trailing, 16)
                            .padding(.bottom, 100)
                    }
                }
            }

            // Overlay controls
            VStack {
                // Top bar
                HStack {
                    // Close button
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.title2)
                            .foregroundColor(.white)
                            .padding(12)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Circle())
                    }

                    Spacer()

                    // File name
                    Text(file.fileName)
                        .font(.subheadline)
                        .foregroundColor(.white)
                        .lineLimit(1)
                        .padding(.horizontal, 8)

                    Spacer()

                    // Download button
                    Button {
                        Task {
                            await downloadOriginal()
                        }
                    } label: {
                        if isDownloading {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                .padding(12)
                                .background(Color.black.opacity(0.5))
                                .clipShape(Circle())
                        } else {
                            Image(systemName: downloadComplete ? "checkmark.circle.fill" : "arrow.down.circle")
                                .font(.title2)
                                .foregroundColor(downloadComplete ? .green : .white)
                                .padding(12)
                                .background(Color.black.opacity(0.5))
                                .clipShape(Circle())
                        }
                    }
                    .disabled(isDownloading)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)

                Spacer()
            }
        }
        .statusBarHidden(true)
        .task {
            await loadImages()
        }
    }

    // MARK: - Gestures

    private var magnificationGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                let newScale = lastScale * value
                scale = min(max(newScale, 0.5), 5.0)
            }
            .onEnded { value in
                let newScale = lastScale * value
                lastScale = min(max(newScale, 1.0), 5.0)
                withAnimation(.easeInOut(duration: 0.2)) {
                    scale = lastScale
                }
            }
    }

    // MARK: - Image Loading

    /// Load medium thumbnail first, then fetch full resolution
    private func loadImages() async {
        // Step 1: Load medium thumbnail
        await loadThumbnail()

        // Step 2: Load full resolution image
        await loadFullImage()
    }

    private func loadThumbnail() async {
        let baseURL = apiClient.currentBaseURL
        guard !baseURL.isEmpty else {
            errorMessage = "服务器地址未配置"
            return
        }

        do {
            let data = try await apiClient.requestData(
                endpoint: "/api/v1/files/thumbnail/\(file.id)",
                queryItems: [URLQueryItem(name: "size", value: "medium")]
            )
            if let image = UIImage(data: data) {
                thumbnailImage = image
            }
        } catch {
            // Thumbnail load failure is non-critical if full image loads
            print("Thumbnail load failed: \(error)")
        }
    }

    private func loadFullImage() async {
        isLoadingFull = true
        defer { isLoadingFull = false }

        do {
            let data = try await apiClient.requestData(
                endpoint: "/api/v1/files/download/\(file.id)"
            )
            if let image = UIImage(data: data) {
                fullImage = image
            }
        } catch {
            // If we don't have any image yet, show error
            if thumbnailImage == nil {
                errorMessage = "图片加载失败"
            }
        }
    }

    // MARK: - Download Original

    private func downloadOriginal() async {
        isDownloading = true
        defer { isDownloading = false }

        do {
            let data = try await apiClient.requestData(
                endpoint: "/api/v1/files/download/\(file.id)"
            )
            if let image = UIImage(data: data) {
                // Save to photo library
                UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
                downloadComplete = true
            }
        } catch {
            errorMessage = "下载失败"
        }
    }
}

#Preview {
    ImagePreviewView(
        file: FileBrowseInfo(
            id: 1,
            fileName: "IMG_0001.jpg",
            fileSize: 2_500_000,
            mimeType: "image/jpeg",
            exifTime: "2025-01-15T10:30:00",
            thumbnailUrl: "/api/v1/files/thumbnail/1?size=small",
            createdAt: "2025-01-15T10:30:00"
        ),
        apiClient: .shared
    )
}
