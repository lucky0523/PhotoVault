import Foundation
import Network
import Combine

// MARK: - Connection Types

/// Represents the type of network connection to the server
enum ConnectionType: String {
    case lan = "局域网"
    case wan = "公网"
}

/// Represents the current connection state
enum ConnectionState: Equatable {
    case connected(type: ConnectionType)
    case connecting
    case disconnected

    static func == (lhs: ConnectionState, rhs: ConnectionState) -> Bool {
        switch (lhs, rhs) {
        case (.connecting, .connecting):
            return true
        case (.disconnected, .disconnected):
            return true
        case (.connected(let a), .connected(let b)):
            return a == b
        default:
            return false
        }
    }

    /// Display text for the connection state
    var displayText: String {
        switch self {
        case .connected(let type):
            return type.rawValue
        case .connecting:
            return "连接中..."
        case .disconnected:
            return "未连接"
        }
    }

    /// Whether the connection is active
    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }
}

// MARK: - Connection Manager

/// Manages LAN/WAN connectivity with the PhotoVault server.
/// Uses Network framework (NWPathMonitor) for WiFi detection and implements
/// the LAN-first connection strategy with fallback to WAN.
class ConnectionManager: ObservableObject {
    // MARK: - Published State

    @Published private(set) var connectionState: ConnectionState = .disconnected
    @Published private(set) var isOnWiFi: Bool = false

    // MARK: - Properties

    private var lanAddress: String?
    private var wanAddress: String?
    private var currentServerAddress: String?

    private let pathMonitor: NWPathMonitor
    private let monitorQueue = DispatchQueue(label: "com.photovault.networkMonitor")
    private var healthCheckTimer: Timer?
    private var cancellables = Set<AnyCancellable>()

    /// LAN connection timeout in seconds
    private let lanTimeout: TimeInterval = 10
    /// WAN connection timeout in seconds
    private let wanTimeout: TimeInterval = 15
    /// Health check interval in seconds
    private let healthCheckInterval: TimeInterval = 30

    // MARK: - Singleton

    static let shared = ConnectionManager()

    // MARK: - Initialization

    init() {
        self.pathMonitor = NWPathMonitor()
        setupPathMonitor()
    }

    deinit {
        stopHealthCheck()
        pathMonitor.cancel()
    }

    // MARK: - Public Methods

    /// Connect to the server, trying LAN first then WAN.
    /// - Parameter serverAddress: The server address (can be LAN or WAN)
    @MainActor
    func connect(serverAddress: String) async {
        connectionState = .connecting

        // Parse the address - try as LAN first
        let normalizedAddress = normalizeAddress(serverAddress)

        // Try LAN connection first
        if await tryConnect(address: normalizedAddress, timeout: lanTimeout) {
            lanAddress = normalizedAddress
            currentServerAddress = normalizedAddress
            connectionState = .connected(type: .lan)
            APIClient.shared.currentBaseURL = normalizedAddress
            startHealthCheck()
            return
        }

        // If LAN fails, try WAN (same address with WAN timeout)
        if await tryConnect(address: normalizedAddress, timeout: wanTimeout) {
            wanAddress = normalizedAddress
            currentServerAddress = normalizedAddress
            connectionState = .connected(type: .wan)
            APIClient.shared.currentBaseURL = normalizedAddress
            startHealthCheck()
            return
        }

        // Both failed
        connectionState = .disconnected
    }

    /// Connect with separate LAN and WAN addresses
    /// - Parameters:
    ///   - lanAddress: The LAN address to try first
    ///   - wanAddress: The WAN address as fallback
    @MainActor
    func connect(lanAddress: String, wanAddress: String) async {
        connectionState = .connecting

        let normalizedLAN = normalizeAddress(lanAddress)
        let normalizedWAN = normalizeAddress(wanAddress)

        // Try LAN first with 10s timeout
        if await tryConnect(address: normalizedLAN, timeout: lanTimeout) {
            self.lanAddress = normalizedLAN
            self.currentServerAddress = normalizedLAN
            connectionState = .connected(type: .lan)
            APIClient.shared.currentBaseURL = normalizedLAN
            startHealthCheck()
            return
        }

        // LAN failed, try WAN with 15s timeout
        if await tryConnect(address: normalizedWAN, timeout: wanTimeout) {
            self.wanAddress = normalizedWAN
            self.currentServerAddress = normalizedWAN
            connectionState = .connected(type: .wan)
            APIClient.shared.currentBaseURL = normalizedWAN
            startHealthCheck()
            return
        }

        // Both failed
        connectionState = .disconnected
    }

    /// Get the current connection type
    /// - Returns: The current ConnectionType or nil if disconnected
    func getCurrentConnectionType() -> ConnectionType? {
        switch connectionState {
        case .connected(let type):
            return type
        default:
            return nil
        }
    }

    /// Disconnect from the server
    @MainActor
    func disconnect() {
        stopHealthCheck()
        connectionState = .disconnected
        currentServerAddress = nil
    }

    // MARK: - Private Methods

    /// Set up NWPathMonitor to detect WiFi connectivity
    private func setupPathMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.isOnWiFi = path.usesInterfaceType(.wifi)
            }
        }
        pathMonitor.start(queue: monitorQueue)
    }

    /// Try to connect to a specific address with a timeout
    /// - Parameters:
    ///   - address: The server address to test
    ///   - timeout: Connection timeout in seconds
    /// - Returns: Whether the connection was successful
    private func tryConnect(address: String, timeout: TimeInterval) async -> Bool {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        config.waitsForConnectivity = false
        let session = URLSession(configuration: config)

        guard let url = URL(string: "\(address)/api/v1/connection/test") else {
            return false
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = timeout

        do {
            let (_, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                return false
            }
            return (200...299).contains(httpResponse.statusCode)
        } catch {
            return false
        }
    }

    /// Normalize a server address into a proper URL
    private func normalizeAddress(_ address: String) -> String {
        var url = address.trimmingCharacters(in: .whitespacesAndNewlines)
        url = url.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !url.hasPrefix("http://") && !url.hasPrefix("https://") {
            url = "http://\(url)"
        }
        return url
    }

    // MARK: - Health Check

    /// Start periodic connection health checks
    private func startHealthCheck() {
        stopHealthCheck()
        healthCheckTimer = Timer.scheduledTimer(withTimeInterval: healthCheckInterval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                await self?.performHealthCheck()
            }
        }
    }

    /// Stop the health check timer
    private func stopHealthCheck() {
        healthCheckTimer?.invalidate()
        healthCheckTimer = nil
    }

    /// Perform a single health check
    @MainActor
    private func performHealthCheck() async {
        guard let address = currentServerAddress else {
            connectionState = .disconnected
            return
        }

        let isReachable = await tryConnect(address: address, timeout: 10)

        if !isReachable {
            // Current connection lost — attempt reconnection
            connectionState = .disconnected
            stopHealthCheck()
        }
    }
}
