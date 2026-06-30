import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var tokenManager: TokenManager
    @EnvironmentObject private var launchManager: AppLaunchManager
    @EnvironmentObject private var connectionManager: ConnectionManager

    var body: some View {
        Group {
            switch launchManager.state {
            case .checking:
                SplashView()
            case .ready:
                if tokenManager.isAuthenticated {
                    mainTabView
                } else {
                    LoginView()
                }
            }
        }
        .animation(.easeInOut, value: tokenManager.isAuthenticated)
        .animation(.easeInOut, value: launchManager.state == .ready)
        .task {
            await launchManager.performAutoLogin()
        }
    }

    private var mainTabView: some View {
        VStack(spacing: 0) {
            // Connection status bar
            ConnectionStatusBar()
                .environmentObject(connectionManager)

            // Tab view with state preservation
            TabView {
                LocalTabView()
                    .tabItem {
                        Label("本地", systemImage: "photo.on.rectangle")
                    }
                    .tag(0)

                CloudTabView()
                    .tabItem {
                        Label("云端", systemImage: "cloud")
                    }
                    .tag(1)

                BackupTaskTabView()
                    .tabItem {
                        Label("备份任务", systemImage: "arrow.up.circle")
                    }
                    .tag(2)

                SettingsTabView()
                    .tabItem {
                        Label("设置", systemImage: "gear")
                    }
                    .tag(3)
            }
        }
    }
}

// MARK: - Connection Status Bar

/// Top status bar showing the current connection state (green/grey dot + connection type)
struct ConnectionStatusBar: View {
    @EnvironmentObject private var connectionManager: ConnectionManager

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)

            Text(connectionManager.connectionState.displayText)
                .font(.caption)
                .foregroundStyle(statusTextColor)

            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(Color(.systemBackground))
        .overlay(alignment: .bottom) {
            Divider()
        }
    }

    private var statusColor: Color {
        switch connectionManager.connectionState {
        case .connected:
            return .green
        case .connecting:
            return .orange
        case .disconnected:
            return .gray
        }
    }

    private var statusTextColor: Color {
        switch connectionManager.connectionState {
        case .connected:
            return .primary
        case .connecting:
            return .secondary
        case .disconnected:
            return .secondary
        }
    }
}

// MARK: - Splash View

/// Brief loading screen shown while the app checks authentication state on launch.
struct SplashView: View {
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "photo.on.rectangle.angled")
                .font(.system(size: 64))
                .foregroundStyle(.blue)

            Text("PhotoVault")
                .font(.title)
                .fontWeight(.semibold)

            ProgressView()
                .progressViewStyle(.circular)
                .scaleEffect(1.2)

            Text("正在验证登录状态...")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}

#Preview {
    ContentView()
        .environmentObject(TokenManager.shared)
        .environmentObject(AppLaunchManager())
        .environmentObject(ConnectionManager.shared)
}
