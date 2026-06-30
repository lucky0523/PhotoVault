import Foundation

// MARK: - Token Manager

/// Manages access and refresh tokens for authentication.
/// Handles token storage, expiry checking, and auto-refresh logic.
/// Uses iOS Keychain for secure credential persistence.
class TokenManager: ObservableObject {
    // MARK: - Properties

    @Published private(set) var isAuthenticated: Bool = false

    private let keychain = KeychainManager.shared

    private let accessTokenKey = "com.photovault.accessToken"
    private let refreshTokenKey = "com.photovault.refreshToken"
    private let accessTokenExpiryKey = "com.photovault.accessTokenExpiry"
    private let refreshTokenExpiryKey = "com.photovault.refreshTokenExpiry"

    /// Stored access token
    var accessToken: String? {
        get { keychain.load(key: accessTokenKey) }
        set {
            if let value = newValue {
                try? keychain.save(key: accessTokenKey, value: value)
            } else {
                keychain.delete(key: accessTokenKey)
            }
            updateAuthState()
        }
    }

    /// Stored refresh token
    var refreshToken: String? {
        get { keychain.load(key: refreshTokenKey) }
        set {
            if let value = newValue {
                try? keychain.save(key: refreshTokenKey, value: value)
            } else {
                keychain.delete(key: refreshTokenKey)
            }
        }
    }

    /// Access token expiry date
    var accessTokenExpiry: Date? {
        get {
            guard let string = keychain.load(key: accessTokenExpiryKey),
                  let interval = Double(string), interval > 0 else {
                return nil
            }
            return Date(timeIntervalSince1970: interval)
        }
        set {
            if let date = newValue {
                try? keychain.save(key: accessTokenExpiryKey, value: String(date.timeIntervalSince1970))
            } else {
                keychain.delete(key: accessTokenExpiryKey)
            }
        }
    }

    /// Refresh token expiry date
    var refreshTokenExpiry: Date? {
        get {
            guard let string = keychain.load(key: refreshTokenExpiryKey),
                  let interval = Double(string), interval > 0 else {
                return nil
            }
            return Date(timeIntervalSince1970: interval)
        }
        set {
            if let date = newValue {
                try? keychain.save(key: refreshTokenExpiryKey, value: String(date.timeIntervalSince1970))
            } else {
                keychain.delete(key: refreshTokenExpiryKey)
            }
        }
    }

    /// Check if access token has expired
    var isAccessTokenExpired: Bool {
        guard let expiry = accessTokenExpiry else { return true }
        // Consider expired if less than 60 seconds remaining
        return Date().addingTimeInterval(60) >= expiry
    }

    /// Check if refresh token has expired
    var isRefreshTokenExpired: Bool {
        guard let expiry = refreshTokenExpiry else { return true }
        return Date() >= expiry
    }

    /// Check if we have valid tokens that can be used or refreshed
    var hasValidSession: Bool {
        if !isAccessTokenExpired {
            return true
        }
        // Access token expired but refresh token might still be valid
        return refreshToken != nil && !isRefreshTokenExpired
    }

    // MARK: - Singleton

    static let shared = TokenManager()

    // MARK: - Initialization

    init() {
        updateAuthState()
    }

    // MARK: - Public Methods

    /// Store tokens received from login or refresh response
    /// - Parameters:
    ///   - accessToken: The JWT access token
    ///   - refreshToken: The JWT refresh token
    ///   - expiresIn: Access token lifetime in seconds
    func storeTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.accessTokenExpiry = Date().addingTimeInterval(TimeInterval(expiresIn))
        // Refresh token valid for 7 days (per design doc)
        self.refreshTokenExpiry = Date().addingTimeInterval(7 * 24 * 60 * 60)
        updateAuthState()
    }

    /// Attempt to refresh the access token using the stored refresh token.
    /// This is called automatically by AuthRequestInterceptor when the access token expires.
    func refreshAccessToken() async throws {
        guard let currentRefreshToken = refreshToken else {
            throw APIError.tokenExpired
        }

        guard !isRefreshTokenExpired else {
            clearTokens()
            throw APIError.tokenExpired
        }

        do {
            let response = try await APIClient.shared.refreshToken(currentRefreshToken)
            storeTokens(
                accessToken: response.accessToken,
                refreshToken: response.refreshToken,
                expiresIn: response.expiresIn
            )
        } catch {
            // If refresh fails, clear all tokens
            clearTokens()
            throw APIError.tokenExpired
        }
    }

    /// Clear all stored tokens (logout)
    func clearTokens() {
        keychain.delete(key: accessTokenKey)
        keychain.delete(key: refreshTokenKey)
        keychain.delete(key: accessTokenExpiryKey)
        keychain.delete(key: refreshTokenExpiryKey)
        updateAuthState()
    }

    // MARK: - Private Helpers

    private func updateAuthState() {
        DispatchQueue.main.async { [weak self] in
            self?.isAuthenticated = self?.hasValidSession ?? false
        }
    }
}
