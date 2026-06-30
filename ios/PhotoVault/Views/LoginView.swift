import SwiftUI

// MARK: - Login View

/// Login screen with server address, username, password fields,
/// test connection button, remember password toggle, and login button.
struct LoginView: View {
    @StateObject private var viewModel = LoginViewModel()
    @State private var showPassword: Bool = false
    @State private var showTestAlert: Bool = false
    @State private var testAlertMessage: String = ""
    @State private var testAlertSuccess: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Spacer().frame(height: 40)

                // MARK: - Logo & Title
                logoSection

                // MARK: - Error Banner
                if let error = viewModel.errorMessage {
                    errorBanner(message: error)
                }

                // MARK: - Form Fields
                formSection

                // MARK: - Remember Password
                rememberPasswordToggle

                // MARK: - Buttons
                buttonsSection

                Spacer().frame(height: 40)
            }
            .padding(.horizontal, 24)
        }
        .background(Color(.systemGroupedBackground))
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .alert(testAlertMessage, isPresented: $showTestAlert) {
            Button("确定", role: .cancel) {}
        }
        .onChange(of: viewModel.testConnectionResult) { _, result in
            guard let result = result else { return }
            switch result {
            case .success:
                testAlertMessage = "✓ 连接成功"
                testAlertSuccess = true
            case .failure(let message):
                testAlertMessage = "✗ 连接失败: \(message)"
                testAlertSuccess = false
            }
            showTestAlert = true
            viewModel.clearTestResult()
        }
    }

    // MARK: - Logo Section

    private var logoSection: some View {
        VStack(spacing: 8) {
            Image(systemName: "icloud.fill")
                .font(.system(size: 56))
                .foregroundColor(.blue)

            Text("PhotoVault")
                .font(.title)
                .fontWeight(.bold)
                .foregroundColor(.primary)

            Text("图片备份服务")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .padding(.bottom, 8)
    }

    // MARK: - Error Banner

    private func errorBanner(message: String) -> some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(.white)
            Text(message)
                .font(.subheadline)
                .foregroundColor(.white)
            Spacer()
        }
        .padding(12)
        .background(Color.red)
        .cornerRadius(8)
    }

    // MARK: - Form Section

    private var formSection: some View {
        VStack(spacing: 16) {
            // Server Address
            VStack(alignment: .leading, spacing: 4) {
                Text("服务器地址")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                TextField("192.168.1.100:8080", text: $viewModel.serverAddress)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(viewModel.serverAddressError != nil ? Color.red : Color.clear, lineWidth: 1)
                    )
                    .disabled(viewModel.isLoading)
                    .onChange(of: viewModel.serverAddress) { _, _ in
                        viewModel.clearServerAddressError()
                    }
                if let error = viewModel.serverAddressError {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.red)
                }
            }

            // Username
            VStack(alignment: .leading, spacing: 4) {
                Text("用户名")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                TextField("用户名", text: $viewModel.username)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(viewModel.usernameError != nil ? Color.red : Color.clear, lineWidth: 1)
                    )
                    .disabled(viewModel.isLoading)
                    .onChange(of: viewModel.username) { _, _ in
                        viewModel.clearUsernameError()
                    }
                if let error = viewModel.usernameError {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.red)
                }
            }

            // Password
            VStack(alignment: .leading, spacing: 4) {
                Text("密码")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                HStack {
                    if showPassword {
                        TextField("密码", text: $viewModel.password)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    } else {
                        SecureField("密码", text: $viewModel.password)
                    }
                    Button(action: { showPassword.toggle() }) {
                        Image(systemName: showPassword ? "eye.slash.fill" : "eye.fill")
                            .foregroundColor(.secondary)
                    }
                }
                .padding(8)
                .background(Color(.systemBackground))
                .cornerRadius(6)
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(
                            viewModel.passwordError != nil ? Color.red : Color(.systemGray4),
                            lineWidth: 1
                        )
                )
                .disabled(viewModel.isLoading)
                .onChange(of: viewModel.password) { _, _ in
                    viewModel.clearPasswordError()
                }
                if let error = viewModel.passwordError {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.red)
                }
            }
        }
        .padding(20)
        .background(Color(.systemBackground))
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.05), radius: 4, x: 0, y: 2)
    }

    // MARK: - Remember Password Toggle

    private var rememberPasswordToggle: some View {
        Toggle(isOn: $viewModel.rememberPassword) {
            Text("记住密码")
                .font(.subheadline)
        }
        .toggleStyle(CheckboxToggleStyle())
        .disabled(viewModel.isLoading)
    }

    // MARK: - Buttons Section

    private var buttonsSection: some View {
        VStack(spacing: 12) {
            // Test Connection Button
            Button(action: { viewModel.testConnection() }) {
                HStack {
                    if viewModel.isTesting {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle())
                            .scaleEffect(0.8)
                    }
                    Text("测试连接")
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .buttonStyle(.bordered)
            .disabled(viewModel.isLoading || viewModel.isTesting)

            // Login Button
            Button(action: { viewModel.login() }) {
                HStack {
                    if viewModel.isLoading {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            .scaleEffect(0.8)
                    }
                    Text("登录")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .disabled(viewModel.isLoading || viewModel.isTesting)
        }
    }
}

// MARK: - Checkbox Toggle Style

/// A custom toggle style that renders as a checkbox
struct CheckboxToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack {
            Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                .foregroundColor(configuration.isOn ? .blue : .secondary)
                .font(.system(size: 20))
                .onTapGesture {
                    configuration.isOn.toggle()
                }
            configuration.label
        }
    }
}

// MARK: - Preview

#Preview {
    LoginView()
}
