import SwiftUI
import BackgroundTasks

@main
struct PhotoVaultApp: App {
    // MARK: - Core Data

    let persistenceController = PersistenceController.shared

    // MARK: - Managers

    @StateObject private var tokenManager = TokenManager.shared
    @StateObject private var launchManager = AppLaunchManager()
    @StateObject private var connectionManager = ConnectionManager.shared

    // MARK: - Initialization

    init() {
        // Register background tasks before app finishes launching
        BackgroundTaskManager.shared.registerBackgroundTasks()

        // Set up API client with auth interceptor
        let authInterceptor = AuthRequestInterceptor(tokenManager: TokenManager.shared)
        APIClient.shared.addInterceptor(authInterceptor)
    }

    // MARK: - Body

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(\.managedObjectContext, persistenceController.viewContext)
                .environmentObject(tokenManager)
                .environmentObject(launchManager)
                .environmentObject(connectionManager)
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                    BackgroundTaskManager.shared.applicationDidEnterBackground()
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
                    BackgroundTaskManager.shared.applicationDidBecomeActive()
                }
        }
    }
}
