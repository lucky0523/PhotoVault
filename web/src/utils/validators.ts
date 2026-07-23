/**
 * Shared password validation rules.
 *
 * Single source of truth — used by RegisterView and AdminUsersView.
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
