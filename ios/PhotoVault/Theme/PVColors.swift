import SwiftUI

/// PhotoVault 品牌色 —— 与 Android (PhotoVaultColors) 和 Web (theme.css) 保持一致。
///
/// 色彩语言：
/// - Vault Blue:  主操作、选中态、链接、Tab 高亮
/// - Sync Green:  成功、已备份、连接成功
/// - Archive Amber: 警告、回收站、待处理
/// - Delete Rose: 危险、删除、错误
enum PVColors {
    // MARK: - Vault Blue (主色)
    static let vaultBlue = Color(red: 0.184, green: 0.420, blue: 1.0)       // #2F6BFF
    static let vaultBlueLight = Color(red: 0.416, green: 0.584, blue: 1.0)  // #6A95FF

    // MARK: - Sync Green (成功)
    static let syncGreen = Color(red: 0.0, green: 0.816, blue: 0.400)       // #00D066
    static let syncGreenLight = Color(red: 0.302, green: 0.859, blue: 0.561) // #4DDB8F

    // MARK: - Archive Amber (警告)
    static let archiveAmber = Color(red: 1.0, green: 0.663, blue: 0.122)    // #FFA91F
    static let archiveAmberLight = Color(red: 1.0, green: 0.761, blue: 0.361) // #FFC25C

    // MARK: - Delete Rose (危险)
    static let deleteRose = Color(red: 1.0, green: 0.361, blue: 0.447)      // #FF5C72
    static let deleteRoseLight = Color(red: 1.0, green: 0.553, blue: 0.612) // #FF8D9C

    // MARK: - Neutrals
    static let softSlate = Color(red: 0.345, green: 0.404, blue: 0.494)     // #58677E
    static let mist = Color(red: 0.969, green: 0.980, blue: 1.0)            // #F7FAFF
}
