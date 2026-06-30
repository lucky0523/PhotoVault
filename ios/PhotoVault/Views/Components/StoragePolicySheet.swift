import SwiftUI

// MARK: - Storage Policy Sheet

/// Bottom sheet for configuring storage policy of a backup folder.
/// Contains toggles for custom path and year/month layering,
/// path preview, and character validation.
struct StoragePolicySheet: View {
    // MARK: - Properties

    let folder: BackupFolder
    let onSave: (Bool, String?, Bool) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var useCustomPath: Bool
    @State private var customPath: String
    @State private var useYearMonthLayer: Bool
    @State private var pathValidationError: String? = nil

    // MARK: - Initialization

    init(folder: BackupFolder, onSave: @escaping (Bool, String?, Bool) -> Void) {
        self.folder = folder
        self.onSave = onSave
        _useCustomPath = State(initialValue: folder.useCustomPath)
        _customPath = State(initialValue: folder.customPath ?? "")
        _useYearMonthLayer = State(initialValue: folder.useYearMonthLayer)
    }

    // MARK: - Body

    var body: some View {
        NavigationStack {
            Form {
                // MARK: - Custom Path Toggle
                Section {
                    Toggle("手动指定存储目录", isOn: $useCustomPath)

                    if useCustomPath {
                        VStack(alignment: .leading, spacing: 6) {
                            TextField("输入存储路径", text: $customPath)
                                .textFieldStyle(.roundedBorder)
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                                .onChange(of: customPath) { _, newValue in
                                    validatePath(newValue)
                                }

                            if let error = pathValidationError {
                                Text(error)
                                    .font(.caption)
                                    .foregroundColor(.red)
                            }
                        }
                    }
                } header: {
                    Text("存储目录")
                }

                // MARK: - Year/Month Toggle
                Section {
                    Toggle("按年月分层", isOn: $useYearMonthLayer)
                } header: {
                    Text("目录结构")
                }

                // MARK: - Path Preview
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("存储路径示例")
                            .font(.subheadline)
                            .foregroundColor(.secondary)

                        Text(resolvedPathPreview)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundColor(.primary)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(.systemGray6))
                            .cornerRadius(8)
                    }
                } header: {
                    Text("路径预览")
                }
            }
            .navigationTitle("配置存储策略 — \(folder.folderName)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        save()
                    }
                    .disabled(!canSave)
                }
            }
        }
    }

    // MARK: - Computed Properties

    /// Whether the save button should be enabled
    private var canSave: Bool {
        if useCustomPath {
            return !customPath.trimmingCharacters(in: .whitespaces).isEmpty
                && pathValidationError == nil
        }
        return true
    }

    /// Generate a preview of the resolved storage path
    private var resolvedPathPreview: String {
        let basePath: String
        if useCustomPath && !customPath.trimmingCharacters(in: .whitespaces).isEmpty {
            basePath = customPath
        } else {
            basePath = "/data/{user}/{device}"
        }

        let folderComponent = folder.folderName
        var preview = "\(basePath)/\(folderComponent)/"

        if useYearMonthLayer {
            let now = Date()
            let calendar = Calendar.current
            let year = calendar.component(.year, from: now)
            let month = String(format: "%02d", calendar.component(.month, from: now))
            preview += "\(year)/\(month)/"
        }

        return preview
    }

    // MARK: - Private Methods

    /// Validate path characters — only allows letters, numbers, /, _, -, .
    private func validatePath(_ path: String) {
        let allowedCharacters = CharacterSet.letters
            .union(.decimalDigits)
            .union(CharacterSet(charactersIn: "/_-."))

        let inputCharacters = CharacterSet(charactersIn: path)

        if path.isEmpty {
            pathValidationError = nil
        } else if !allowedCharacters.isSuperset(of: inputCharacters) {
            pathValidationError = "路径仅允许字母、数字、/、_、-、."
        } else {
            pathValidationError = nil
        }
    }

    /// Save policy and dismiss
    private func save() {
        let trimmedPath = customPath.trimmingCharacters(in: .whitespaces)
        onSave(useCustomPath, useCustomPath ? trimmedPath : nil, useYearMonthLayer)
        dismiss()
    }
}

// MARK: - Preview

#Preview {
    StoragePolicySheet(
        folder: {
            let controller = PersistenceController(inMemory: true)
            let folder = BackupFolder.create(
                in: controller.viewContext,
                folderPath: "/DCIM/Camera",
                folderName: "Camera"
            )
            return folder
        }(),
        onSave: { _, _, _ in }
    )
}
