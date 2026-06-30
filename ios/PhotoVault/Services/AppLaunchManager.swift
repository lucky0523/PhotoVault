import Foundation

// MARK: - App Launch Manager

/// Handles the auto-login flow on app startup.
/// Restores saved server address, checks token validity,
/// and refreshes the access token if needed.
@MainActor
class AppLaunchManager: ObservableObject {
    // MARK: - Published State

    enum LaunchState {
        case checking   // Initial state: validating session
        case ready      // Auth check complete, app can proceed
    }

    @Published private(set) var state: LaunchState = .checking

    // MARK: - Dependencies

    private let tokenManager: TokenManager
    private let apiClient: APIClient

    private let savedServerAddressKey = "com.photovault.savedServerAddress"

    // MARK: - Initialization

    init(tokenManager: TokenManager = .shared, apiClient: APIClient = .shared) {
        self.tokenManager = tokenManager
        self.apiClient = apiClient
    }

    // MARK: - Public Methods

    /// Perform the auto-login check on app launch.
    /// Restores saved server address and refreshes token if needed.
    func performAutoLogin() async {
        // If no valid session at all, skip straight to login
        guard tokenManager.hasValidSession else {
            state = .ready
            return
        }

        // Restore saved server address so APIClient can make requests
        restoreServerAddress()

        // If access token is still valid, we're good to go
        if !tokenManager.isAccessTokenExpired {
            state = .ready
            return
        }

        // Access token expired but refresh token is valid — try to refresh
        do {
            try await tokenManager.refreshAccessToken()
        } catch {
            // Refresh failed — token manager will clear tokens,
            // user will see login screen
        }

        state = .ready
    }

    // MARK: - Private Methods

    /// Restore the saved server address from UserDefaults and set it on APIClient
    private func restoreServerAddress() {
        guard let savedAddress = UserDefaults.standard.string(forKey: savedServerAddressKey),
              !savedAddress.isEmpty else {
            return
        }

        let normalizedURL = normalizeURL(savedAddress)
        apiClient.currentBaseURL = normalizedURL
    }

    /// Normalize a server address into a proper URL string
    private func normalizeURL(_ address: String) -> String {
        var url = address.trimmingCharacters(in: .whitespacesAndNewlines)
        url = url.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !url.hasPrefix("http://") && !url.hasPrefix("https://") {
            url = "http://\(url)"
        }
        return url
    }
}
