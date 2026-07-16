import SwiftUI

/// 设置 Tab - 显示备份条件配置、存储策略管理、账户信息和退出登录
struct SettingsTabView: View {
    @StateObject private var viewModel = SettingsViewModel()
    @State private var selectedFolderForPolicy: BackupFolder? = nil
    @State private var showPolicySheet: Bool = false

    var body: some View {
        NavigationStack {
            Form {
                // MARK: - 备份条件
                backupConditionsSection

                // MARK: - 存储策略管理
                storagePolicySection

                // MARK: - 账户信息
                accountInfoSection

                // MARK: - 退出登录
                logoutSection
            }
            .navigationTitle("设置")
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
            .alert("确认退出", isPresented: $viewModel.showLogoutConfirmation) {
                Button("取消", role: .cancel) {}
                Button("退出登录", role: .destructive) {
                    viewModel.logout()
                }
            } message: {
                Text("退出登录将清除本地保存的凭证信息，确定要退出吗？")
            }
        }
    }

    // MARK: - Backup Conditions Section

    private var backupConditionsSection: some View {
        Section {
            // Auto-backup switch (R-3.8): controls all automatic triggers.
            HStack {
                Label("自动备份", systemImage: "arrow.triangle.2.circlepath")
                Spacer()
                Toggle("", isOn: $viewModel.autoBackupEnabled)
            }

            // WiFi switch (always ON, disabled)
            HStack {
                Label("仅 WiFi 备份", systemImage: "wifi")
                Spacer()
                Toggle("", isOn: $viewModel.wifiRequired)
                    .disabled(true)
            }

            // Minimum battery level slider
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label("最低电量", systemImage: "battery.50percent")
                    Spacer()
                    Text(viewModel.batteryLevelText)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .monospacedDigit()
                }

                Slider(
                    value: $viewModel.minimumBatteryLevel,
                    in: 0.20...0.80,
                    step: 0.05
                )
                .tint(.green)

                HStack {
                    Text("20%")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Spacer()
                    Text("80%")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }

            // Scan interval picker
            HStack {
                Label("扫描间隔", systemImage: "clock.arrow.circlepath")
                Spacer()
                Picker("", selection: $viewModel.scanInterval) {
                    ForEach(ScanInterval.allCases) { interval in
                        Text(interval.displayText).tag(interval)
                    }
                }
                .pickerStyle(.menu)
            }
        } header: {
            Text("备份条件")
        } footer: {
            Text("关闭「自动备份」后仅手动备份可用；备份仅在 WiFi 连接且电量高于设定值时进行")
        }
    }

    // MARK: - Storage Policy Section

    private var storagePolicySection: some View {
        Section {
            if viewModel.backupFolders.isEmpty {
                HStack {
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "folder")
                            .font(.title2)
                            .foregroundColor(.secondary)
                        Text("暂无已配置的备份文件夹")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Text("请在「本地」Tab 中添加备份文件夹")
                            .font(.caption)
                            .foregroundColor(.tertiary)
                    }
                    .padding(.vertical, 12)
                    Spacer()
                }
            } else {
                ForEach(viewModel.backupFolders, id: \.id) { folder in
                    Button {
                        selectedFolderForPolicy = folder
                        showPolicySheet = true
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(folder.folderName)
                                    .font(.body)
                                    .foregroundColor(.primary)
                                Text(viewModel.policySummary(for: folder))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
        } header: {
            Text("存储策略管理")
        }
    }

    // MARK: - Account Info Section

    private var accountInfoSection: some View {
        Section {
            HStack {
                Label("用户名", systemImage: "person")
                Spacer()
                Text(viewModel.currentUsername)
                    .foregroundColor(.secondary)
            }

            HStack {
                Label("服务器地址", systemImage: "server.rack")
                Spacer()
                Text(viewModel.serverAddress)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            HStack {
                Label("连接状态", systemImage: connectionIcon)
                Spacer()
                Text(viewModel.connectionDisplayText)
                    .foregroundColor(connectionColor)
            }
        } header: {
            Text("账户信息")
        }
    }

    // MARK: - Logout Section

    private var logoutSection: some View {
        Section {
            Button(role: .destructive) {
                viewModel.showLogoutConfirmation = true
            } label: {
                HStack {
                    Spacer()
                    Text("退出登录")
                        .fontWeight(.medium)
                    Spacer()
                }
            }
        }
    }

    // MARK: - Helpers

    private var connectionIcon: String {
        if viewModel.connectionDisplayText.contains("已连接") {
            return "circle.fill"
        } else {
            return "circle"
        }
    }

    private var connectionColor: Color {
        if viewModel.connectionDisplayText.contains("已连接") {
            return .green
        } else if viewModel.connectionDisplayText.contains("连接中") {
            return .orange
        } else {
            return .secondary
        }
    }
}

#Preview {
    SettingsTabView()
}
