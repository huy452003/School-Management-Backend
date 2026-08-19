/**
 * Chuẩn backend: com.model_shared.models.Response
 * { status, message, modelName, errors: Record<string,string>, data }
 * MethodArgumentNotValidException: errors = { firstName: "...", email: "..." }
 */
export function formatApiError(error, fallbackMessage = 'Đã xảy ra lỗi.') {
  const data = error?.response?.data
  if (!data) {
    if (error?.message === 'Network Error' || !error?.response) {
      return fallbackMessage
    }
    return error?.message || fallbackMessage
  }

  const { message, errors } = data

  if (errors && typeof errors === 'object') {
    const entries = Object.entries(errors).filter(([, v]) => v != null && String(v).trim() !== '')
    if (entries.length > 0) {
      return entries
        .map(([field, msg]) => {
          const text = String(msg).trim()
          if (field === 'Error' || field === 'Details' || field === 'InvalidFields') {
            return text
          }
          return `${field}: ${text}`
        })
        .join('\n')
    }
  }

  return message || fallbackMessage
}
