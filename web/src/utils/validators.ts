/**
 * Shared password and username validation rules.
 *
 * Single source of truth on the Web side — used by RegisterView, AdminUsersView
 * and (username only) SetupView.
 */

const ALLOWED_PASSWORD_PATTERN = /^[A-Za-z0-9!@#$%^&*()_+\-=\[\]{}|;:'",.<>/?`~\\]+$/

const PASSWORD_MIN_LENGTH = 8

const PASSWORD_CHARS_MESSAGE =
  '密码只能包含英文字母、数字和符号 !@#$%^&*()_+-=[]{}|;:\'",.<>/?`~\\'

/**
 * Element Plus form validator for password character rules.
 */
export const validatePasswordChars = (_rule: any, value: string, callback: any) => {
  if (!value) {
    callback()
    return
  }
  if (value.length < PASSWORD_MIN_LENGTH) {
    callback(new Error(`密码长度不能少于${PASSWORD_MIN_LENGTH}位`))
    return
  }
  if (!ALLOWED_PASSWORD_PATTERN.test(value)) {
    callback(new Error(PASSWORD_CHARS_MESSAGE))
  } else {
    callback()
  }
}

/*
 * Username rules for NEW accounts (RegisterView, AdminUsersView, SetupView).
 *
 * Mirror of server/app/core/validators.py (USERNAME_PATTERN, RESERVED_USERNAMES,
 * BOOTSTRAP_ADMIN_USERNAME, USERNAME_RULE_MESSAGE, USERNAME_RESERVED_MESSAGE).
 * Keep identical; server/tests/test_username_rules_contract.py fails on drift.
 * The server stays authoritative: its error message is still shown on submit.
 */

export const USERNAME_PATTERN = /^[a-z0-9][a-z0-9_-]{1,30}[a-z0-9]$/

export const RESERVED_USERNAMES: ReadonlySet<string> = new Set([
  // Product decision
  'admin', 'root', 'system', 'api', 'static',
  // API route prefixes under /api/v1
  'setup', 'auth', 'connection', 'backup', 'files', 'server', 'explore',
  'stats', 'clients', 'fnos', 'health',
  // Static mount
  'assets',
  // Top-level SPA routes
  'photos', 'timeline', 'statistics', 'trash', 'settings', 'devices',
  'login', 'register', 'fnos-auth-callback', 'app-auth',
  // Directories next to the user dirs in the storage root
  'logs', 'client-releases',
  // App name
  'photovault',
  // Windows device names
  'con', 'prn', 'aux', 'nul',
  'com1', 'com2', 'com3', 'com4', 'com5', 'com6', 'com7', 'com8', 'com9',
  'lpt1', 'lpt2', 'lpt3', 'lpt4', 'lpt5', 'lpt6', 'lpt7', 'lpt8', 'lpt9',
])

export const BOOTSTRAP_ADMIN_USERNAME = 'admin'

export const USERNAME_RULE_MESSAGE = '用户名需为 3-32 位，只能包含小写英文字母、数字、下划线（_）和连字符（-），并以字母或数字开头和结尾'

export const USERNAME_RESERVED_MESSAGE = '该用户名为系统保留名称，请换一个用户名'

/** Helper text shown under the username field. */
export const USERNAME_HINT = '3-32 位小写英文字母、数字、_ 或 -，首尾须为字母或数字；部分系统保留名不可用'

/**
 * Same logic as the server's validate_username: null if valid, else the message.
 * The value is checked as typed (no trimming or lowercasing).
 */
export function usernameError(
  value: string,
  options: { bootstrapAdmin?: boolean } = {},
): string | null {
  if (!USERNAME_PATTERN.test(value)) return USERNAME_RULE_MESSAGE
  if (RESERVED_USERNAMES.has(value.toLowerCase())) {
    if (options.bootstrapAdmin && value === BOOTSTRAP_ADMIN_USERNAME) return null
    return USERNAME_RESERVED_MESSAGE
  }
  return null
}

function usernameValidator(bootstrapAdmin: boolean) {
  return (_rule: any, value: string, callback: any) => {
    // Empty values are left to the `required` rule.
    if (!value) {
      callback()
      return
    }
    const message = usernameError(value, { bootstrapAdmin })
    if (message) {
      callback(new Error(message))
    } else {
      callback()
    }
  }
}

/** Element Plus form validator for new usernames (register, admin create). */
export const validateUsername = usernameValidator(false)

/** Element Plus form validator for the first administrator in the setup wizard. */
export const validateBootstrapAdminUsername = usernameValidator(true)
