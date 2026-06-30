import Foundation
import Combine

// MARK: - Login View Model

/// ViewModel for the login screen. Handles form state, validation,
/// test connection, and login logic.
@MainActor
class LoginViewModel: ObservableObject {
    // MARK: - Published Properties

    @Published var serverAddress: String = ""
    @Published var username: String = ""
    @Published var password: String = ""
    @Published var rememberPassword: Bool = false

    @Published var isLoading: Bool = false
    @Published var isTesting: Bool = false

    @Published var errorMessage: String? = nil
    @Published var testConnectionResult: TestConnectionResult? = nil

    @Published var serverAddressError: String? = nil
    @Published var usernameError: String? = nil
    @Published var passwordError: String? = nil

    @Published var loginSuccess: Bool = false

    // MARK: - Dependencies

    private let tokenManager: TokenManager
    private let apiClient: APIClient

    // MARK: - Credential Storage Keys

    private let savedServerAddressKey = "com.photovault.savedServerAddress"
    private let savedUsernameKey = "com.photovault.savedUsername"
    private let savedPasswordKey = "com.photovault.savedPassword"
    private let savedRememberPasswordKey = "com.photovault.savedRememberPassword"

    // MARK: - Initialization

    init(tokenManager: TokenManager = .shared, apiClient: APIClient = .shared) {
        self.tokenManager = tokenManager
        self.apiClient = apiClient
        loadSavedCredentials()
    }

    // MARK: - Public Methods

    /// Test connection to the server address
    func testConnection() {
        // Validate server address
        let trimmedAddress = serverAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedAddress.isEmpty {
            serverAddressError = "请输入服务器地址"
            return
        }

        serverAddressError = nil
        isTesting = true
        testConnectionResult = nil
        errorMessage = nil

        Task {
            do {
                _ = try await apiClient.testConnection(serverAddress: trimmedAddress)
                isTesting = false
                testConnectionResult = .success
            } catch let error as APIError {
                isTesting = false
                testConnectionResult = .failure(message: error.errorDescription ?? "连接失败")
            } catch {
                isTesting = false
                testConnectionResult = .failure(message: "连接失败: \(error.localizedDescription)")
            }
        }
    }

    /// Perform login with current form values
    func login() {
        // Clear previous errors
        errorMessage = nil

        // Validate all fields
        var hasError = false

        let trimmedServer = serverAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedPassword = password.trimmingCharacters(in: .whitespacesAndNewlines)

        if trimmedServer.isEmpty {
            serverAddressError = "请输入服务器地址"
            hasError = true
        } else {
            serverAddressError = nil
        }

        if trimmedUsername.isEmpty {
            usernameError = "请输入用户名"
            hasError = true
        } else {
            usernameError = nil
        }

        if trimmedPassword.isEmpty {
            passwordError = "请输入密码"
            hasError = true
        } else {
            passwordError = nil
        }

        if hasError { return }

        // Perform login
        isLoading = true

        Task {
            do {
                // Set base URL for API client
                apiClient.currentBaseURL = normalizeURL(trimmedServer)

                let response = try await apiClient.login(
                    username: trimmedUsername,
                    password: password
                )

                // Store tokens
                tokenManager.storeTokens(
                    accessToken: response.accessToken,
                    refreshToken: response.refreshToken,
                    expiresIn: response.expiresIn
                )

                // Save credentials
                saveCredentials(serverAddress: trimmedServer, username: trimmedUsername)

                isLoading = false
                loginSuccess = true
            } catch let error as APIError {
                isLoading = false
                switch error {
                case .unauthorized, .httpError(statusCode: 401, _):
                    errorMessage = "用户名或密码错误"
                case .serverUnreachable, .timeout:
                    errorMessage = "服务器不可达"
                default:
                    errorMessage = error.errorDescription ?? "登录失败"
                }
            } catch {
                isLoading = false
                errorMessage = "登录失败: \(error.localizedDescription)"
            }
        }
    }

    /// Clear field error when user starts editing
    func clearServerAddressError() {
        serverAddressError = nil
    }

    func clearUsernameError() {
        usernameError = nil
    }

    func clearPasswordError() {
        passwordError = nil
    }

    func clearTestResult() {
        testConnectionResult = nil
    }

    // MARK: - Private Methods

    private func loadSavedCredentials() {
        serverAddress = UserDefaults.standard.string(forKey: savedServerAddressKey) ?? ""
        username = UserDefaults.standard.string(forKey: savedUsernameKey) ?? ""
        rememberPassword = UserDefaults.standard.bool(forKey: savedRememberPasswordKey)

        if rememberPassword {
            password = KeychainManager.shared.load(key: savedPasswordKey) ?? ""
        }
    }

    private func saveCredentials(serverAddress: String, username: String) {
        UserDefaults.standard.set(serverAddress, forKey: savedServerAddressKey)
        UserDefaults.standard.set(username, forKey: savedUsernameKey)
        UserDefaults.standard.set(rememberPassword, forKey: savedRememberPasswordKey)

        if rememberPassword {
            try? KeychainManager.shared.save(key: savedPasswordKey, value: password)
        } else {
            KeychainManager.shared.delete(key: savedPasswordKey)
        }
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

// MARK: - Test Connection Result

enum TestConnectionResult {
    case success
    case failure(message: String)
}
