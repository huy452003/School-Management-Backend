/**
 * Tuổi từ chuỗi yyyy-MM-dd (input type="date").
 * Trả về null nếu không hợp lệ.
 */
export function ageFromYyyyMmDd(yyyyMmDd) {
  if (!yyyyMmDd || typeof yyyyMmDd !== 'string') return null
  const m = yyyyMmDd.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!m) return null
  const y = Number(m[1])
  const mo = Number(m[2])
  const d = Number(m[3])
  if (mo < 1 || mo > 12 || d < 1 || d > 31) return null
  const birth = new Date(y, mo - 1, d)
  if (
    birth.getFullYear() !== y ||
    birth.getMonth() !== mo - 1 ||
    birth.getDate() !== d
  ) {
    return null
  }
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const md = today.getMonth() - birth.getMonth()
  if (md < 0 || (md === 0 && today.getDate() < birth.getDate())) age -= 1
  return age >= 0 ? age : null
}
