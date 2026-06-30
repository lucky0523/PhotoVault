import Foundation

// MARK: - API Error Types

/// Custom error enum for API operations
enum APIError: Error, LocalizedError {
    case invalidURL
    case invalidResponse
    case httpError(statusCode: Int, message: String?)
    case decodingError(Error)
    case encodingError(Error)
    case networkError(Error)
    case unauthorized
    case tokenExpired
    case serverUnreachable
    case timeout
    case unknown(Error)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "无效的服务器地址"
        case .invalidResponse:
            return "服务器响应格式错误"
        case .httpError(let code, let message):
            return message ?? "HTTP 错误: \(code)"
        case .decodingError:
            return "数据解析失败"
        case .encodingError:
            return "数据编码失败"
        case .networkError(let error):
            return "网络错误: \(error.localizedDescription)"
        case .unauthorized:
            return "认证失败，请重新登录"
        case .tokenExpired:
            return "登录已过期，请重新登录"
        case .serverUnreachable:
            return "服务器不可达"
        case .timeout:
            return "连接超时"
        case .unknown(let error):
            return "未知错误: \(error.localizedDescription)"
        }
    }
}

// MARK: - HTTP Method

enum HTTPMethod: String {
    case get = "GET"
    case post = "POST"
    case put = "PUT"
    case delete = "DELETE"
}

// MARK: - API Response Models

struct LoginRequest: Codable {
    let username: String
    let password: String
}

struct LoginResponse: Codable {
    let accessToken: String
    let refreshToken: String
    let expiresIn: Int

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
    }
}

struct RefreshTokenRequest: Codable {
    let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case refreshToken = "refresh_token"
    }
}

struct ConnectionTestResponse: Codable {
    let status: String
    let version: String?
}

// MARK: - Request Interceptor Protocol

/// Protocol for intercepting and modifying requests (e.g., adding JWT tokens)
protocol RequestInterceptor {
    func intercept(_ request: inout URLRequest) async throws
}

// MARK: - Token-based Request Interceptor

/// Attaches JWT access token to outgoing requests
class AuthRequestInterceptor: RequestInterceptor {
    private let tokenManager: TokenManager

    init(tokenManager: TokenManager) {
        self.tokenManager = tokenManager
    }

    func intercept(_ request: inout URLRequest) async throws {
        // Skip token attachment for auth endpoints
        let path = request.url?.path ?? ""
        if path.contains("/auth/login") || path.contains("/auth/refresh") || path.contains("/connection/test") {
            return
        }

        guard let token = tokenManager.accessToken else {
            throw APIError.unauthorized
        }

        // Check if token is expired and try to refresh
        if tokenManager.isAccessTokenExpired {
            do {
                try await tokenManager.refreshAccessToken()
                guard let newToken = tokenManager.accessToken else {
                    throw APIError.tokenExpired
                }
                request.setValue("Bearer \(newToken)", forHTTPHeaderField: "Authorization")
            } catch {
                throw APIError.tokenExpired
            }
        } else {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
    }
}

// MARK: - API Client

/// URLSession-based HTTP client for communicating with the PhotoVault server
class APIClient {
    // MARK: - Properties

    private let session: URLSession
    private var baseURL: String
    private var interceptors: [RequestInterceptor] = []

    /// Shared singleton instance
    static let shared = APIClient()

    // MARK: - Configuration

    /// The current base URL for API requests
    var currentBaseURL: String {
        get { baseURL }
        set { baseURL = newValue.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    }

    // MARK: - Initialization

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        config.waitsForConnectivity = false
        self.session = URLSession(configuration: config)
        self.baseURL = ""
    }

    /// Initialize with a custom URLSession (for testing)
    init(session: URLSession, baseURL: String = "") {
        self.session = session
        self.baseURL = baseURL
    }

    // MARK: - Interceptor Management

    func addInterceptor(_ interceptor: RequestInterceptor) {
        interceptors.append(interceptor)
    }

    func removeAllInterceptors() {
        interceptors.removeAll()
    }

    // MARK: - Public API Methods

    /// Test connection to the server
    /// - Parameter serverAddress: The server address to test
    /// - Returns: ConnectionTestResponse if successful
    func testConnection(serverAddress: String) async throws -> ConnectionTestResponse {
        let url = normalizeURL(serverAddress)
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        let testSession = URLSession(configuration: config)

        let requestURL = URL(string: "\(url)/api/v1/connection/test")
        guard let requestURL = requestURL else {
            throw APIError.invalidURL
        }

        var request = URLRequest(url: requestURL)
        request.httpMethod = HTTPMethod.get.rawValue

        do {
            let (data, response) = try await testSession.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.invalidResponse
            }
            guard (200...299).contains(httpResponse.statusCode) else {
                throw APIError.serverUnreachable
            }
            let result = try JSONDecoder().decode(ConnectionTestResponse.self, from: data)
            return result
        } catch let error as APIError {
            throw error
        } catch let error as URLError where error.code == .timedOut {
            throw APIError.timeout
        } catch {
            throw APIError.serverUnreachable
        }
    }

    /// Login with username and password
    /// - Parameters:
    ///   - username: User's username
    ///   - password: User's password
    /// - Returns: LoginResponse containing tokens
    func login(username: String, password: String) async throws -> LoginResponse {
        let body = LoginRequest(username: username, password: password)
        let response: LoginResponse = try await request(
            endpoint: "/api/v1/auth/login",
            method: .post,
            body: body
        )
        return response
    }

    /// Refresh the access token using refresh token
    /// - Parameter refreshToken: The refresh token
    /// - Returns: New LoginResponse with fresh tokens
    func refreshToken(_ refreshToken: String) async throws -> LoginResponse {
        let body = RefreshTokenRequest(refreshToken: refreshToken)
        let response: LoginResponse = try await request(
            endpoint: "/api/v1/auth/refresh",
            method: .post,
            body: body
        )
        return response
    }

    // MARK: - Generic Request Method

    /// Perform an API request with JSON encoding/decoding
    /// - Parameters:
    ///   - endpoint: The API endpoint path (e.g., "/api/v1/auth/login")
    ///   - method: HTTP method
    ///   - body: Optional request body (Encodable)
    ///   - queryItems: Optional query parameters
    /// - Returns: Decoded response of type T
    func request<T: Decodable>(
        endpoint: String,
        method: HTTPMethod = .get,
        body: (any Encodable)? = nil,
        queryItems: [URLQueryItem]? = nil
    ) async throws -> T {
        let url = try buildURL(endpoint: endpoint, queryItems: queryItems)

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method.rawValue
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Accept")

        // Encode body if provided
        if let body = body {
            do {
                let encoder = JSONEncoder()
                encoder.keyEncodingStrategy = .convertToSnakeCase
                urlRequest.httpBody = try encoder.encode(body)
            } catch {
                throw APIError.encodingError(error)
            }
        }

        // Apply interceptors
        for interceptor in interceptors {
            try await interceptor.intercept(&urlRequest)
        }

        // Execute request
        do {
            let (data, response) = try await session.data(for: urlRequest)

            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.invalidResponse
            }

            // Handle HTTP status codes
            switch httpResponse.statusCode {
            case 200...299:
                break
            case 401:
                throw APIError.unauthorized
            case 403:
                throw APIError.httpError(statusCode: 403, message: "权限不足")
            default:
                let message = String(data: data, encoding: .utf8)
                throw APIError.httpError(statusCode: httpResponse.statusCode, message: message)
            }

            // Decode response
            do {
                let decoder = JSONDecoder()
                decoder.keyDecodingStrategy = .convertFromSnakeCase
                return try decoder.decode(T.self, from: data)
            } catch {
                throw APIError.decodingError(error)
            }
        } catch let error as APIError {
            throw error
        } catch let error as URLError where error.code == .timedOut {
            throw APIError.timeout
        } catch let error as URLError {
            throw APIError.networkError(error)
        } catch {
            throw APIError.unknown(error)
        }
    }

    /// Perform a request that returns raw Data (for file downloads, etc.)
    func requestData(
        endpoint: String,
        method: HTTPMethod = .get,
        body: (any Encodable)? = nil,
        queryItems: [URLQueryItem]? = nil
    ) async throws -> Data {
        let url = try buildURL(endpoint: endpoint, queryItems: queryItems)

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method.rawValue

        if let body = body {
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let encoder = JSONEncoder()
            encoder.keyEncodingStrategy = .convertToSnakeCase
            urlRequest.httpBody = try encoder.encode(body)
        }

        // Apply interceptors
        for interceptor in interceptors {
            try await interceptor.intercept(&urlRequest)
        }

        let (data, response) = try await session.data(for: urlRequest)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            if httpResponse.statusCode == 401 {
                throw APIError.unauthorized
            }
            let message = String(data: data, encoding: .utf8)
            throw APIError.httpError(statusCode: httpResponse.statusCode, message: message)
        }

        return data
    }

    // MARK: - Private Helpers

    private func buildURL(endpoint: String, queryItems: [URLQueryItem]? = nil) throws -> URL {
        guard !baseURL.isEmpty else {
            throw APIError.invalidURL
        }

        var urlString = "\(baseURL)\(endpoint)"
        if let queryItems = queryItems, !queryItems.isEmpty {
            var components = URLComponents(string: urlString)
            components?.queryItems = queryItems
            urlString = components?.string ?? urlString
        }

        guard let url = URL(string: urlString) else {
            throw APIError.invalidURL
        }
        return url
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
