import React, { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuth } from '../../contexts/AuthContext'
import './Auth.css'

const Register = () => {
  const [formData, setFormData] = useState({
    username: '',
    password: '',
    firstName: '',
    lastName: '',
    gender: 'NAM',
    birth: '',
    phoneNumber: '',
    email: '',
    type: 'STUDENT',
    schoolClass: '',
    major: '',
    // Teacher fields
    department: '',
    classManaging: '',
  })
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [loading, setLoading] = useState(false)
  const { register } = useAuth()
  const navigate = useNavigate()

  const handleChange = (e) => {
    setFormData({
      ...formData,
      [e.target.name]: e.target.value,
    })
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    // Format birth date từ yyyy-MM-dd sang dd-MM-yyyy
    const formatBirthDate = (dateString) => {
      if (!dateString) return null
      const [year, month, day] = dateString.split('-')
      return `${day}-${month}-${year}`
    }

    // Xác định permissions dựa trên role
    const getPermissions = (type) => {
      if (type === 'STUDENT') {
        return ['STUDENT_READ', 'STUDENT_WRITE']
      } else if (type === 'TEACHER') {
        return ['TEACHER_READ', 'TEACHER_WRITE']
      }
      return []
    }

    const registerData = {
      username: formData.username,
      password: formData.password,
      firstName: formData.firstName,
      lastName: formData.lastName,
      gender: formData.gender,
      birth: formatBirthDate(formData.birth), // Format sang dd-MM-yyyy
      phoneNumber: formData.phoneNumber,
      email: formData.email,
      type: formData.type,
      role: formData.type === 'STUDENT' ? 'STUDENT' : 'TEACHER',
      permissions: getPermissions(formData.type), // Thêm permissions
      // Thêm profileData tùy theo loại tài khoản4  
      ...(formData.type === 'STUDENT' && {
        profileData: {
          graduate: false,
          schoolClass: formData.schoolClass || null,
          major: formData.major || null,
        }
      }),
      ...(formData.type === 'TEACHER' && {
        profileData: {
          department: formData.department || null,
          classManaging: formData.classManaging || null,
        }
      })
    }

    const result = await register(registerData)
    if (result.success) {
      // Hiển thị thông báo thành công
      setError('')
      setSuccess('Đăng ký thành công! Thông tin tài khoản của bạn đã được gửi đến nhà trường để xác thực và kích hoạt. Vui lòng quay lại sau để kiểm tra trạng thái tài khoản.')
      // Reset form
      setFormData({
        username: '',
        password: '',
        firstName: '',
        lastName: '',
        gender: 'NAM',
        birth: '',
        phoneNumber: '',
        email: '',
        type: 'STUDENT',
        schoolClass: '',
        major: '',
        department: '',
        classManaging: '',
      })
      // Redirect về trang login sau 8 giây
      setTimeout(() => {
        navigate('/app/login')
      }, 8000)
    } else {
      setError(result.error)
      setSuccess('')
    }
    setLoading(false)
  }

  return (
    <div className="auth-container">
      <div className="auth-card">
        <h1>Đăng Ký</h1>
        <p className="auth-subtitle">Tạo tài khoản mới</p>

        {error && <div className="error-message">{error}</div>}
        {success && (
          <div className="success-message">
            <div className="success-icon">✅</div>
            <div className="success-content">
              <h3>Đăng ký thành công!</h3>
              <p>{success}</p>
              <p className="success-note">Bạn sẽ được chuyển đến trang đăng nhập sau vài giây...</p>
            </div>
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label htmlFor="username">Tên đăng nhập</label>
            <input
              type="text"
              id="username"
              name="username"
              value={formData.username}
              onChange={handleChange}
              required
              placeholder="Nhập tên đăng nhập"
            />
          </div>

          <div className="form-group">
            <label htmlFor="password">Mật khẩu</label>
            <input
              type="password"
              id="password"
              name="password"
              value={formData.password}
              onChange={handleChange}
              required
              placeholder="Nhập mật khẩu"
            />
          </div>

          <div className="form-row">
            <div className="form-group">
              <label htmlFor="firstName">Họ</label>
              <input
                type="text"
                id="firstName"
                name="firstName"
                value={formData.firstName}
                onChange={handleChange}
                required
                placeholder="Nhập họ"
              />
            </div>

            <div className="form-group">
              <label htmlFor="lastName">Tên</label>
              <input
                type="text"
                id="lastName"
                name="lastName"
                value={formData.lastName}
                onChange={handleChange}
                required
                placeholder="Nhập tên"
              />
            </div>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label htmlFor="gender">Giới tính</label>
              <select
                id="gender"
                name="gender"
                value={formData.gender}
                onChange={handleChange}
                required
              >
                <option value="NAM">Nam</option>
                <option value="NU">Nữ</option>
              </select>
            </div>

            <div className="form-group">
              <label htmlFor="birth">Ngày sinh</label>
              <input
                type="date"
                id="birth"
                name="birth"
                value={formData.birth}
                onChange={handleChange}
                required
              />
            </div>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label htmlFor="phoneNumber">Số điện thoại</label>
              <input
                type="tel"
                id="phoneNumber"
                name="phoneNumber"
                value={formData.phoneNumber}
                onChange={handleChange}
                required
                placeholder="Ví dụ: 0123456789"
              />
            </div>

            <div className="form-group">
              <label htmlFor="email">Email</label>
              <input
                type="email"
                id="email"
                name="email"
                value={formData.email}
                onChange={handleChange}
                required
                placeholder="Ví dụ: user@example.com"
              />
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="type">Loại tài khoản</label>
            <select
              id="type"
              name="type"
              value={formData.type}
              onChange={handleChange}
              required
            >
              <option value="STUDENT">Sinh viên</option>
              <option value="TEACHER">Giáo viên</option>
            </select>
          </div>

          {/* Chỉ hiển thị các field này khi chọn STUDENT */}
          {formData.type === 'STUDENT' && (
            <div className="form-row">
              <div className="form-group">
                <label htmlFor="schoolClass">Lớp</label>
                <input
                  type="text"
                  id="schoolClass"
                  name="schoolClass"
                  value={formData.schoolClass}
                  onChange={handleChange}
                  placeholder="Ví dụ: CNTT2024A"
                />
              </div>

              <div className="form-group">
                <label htmlFor="major">Chuyên ngành</label>
                <input
                  type="text"
                  id="major"
                  name="major"
                  value={formData.major}
                  onChange={handleChange}
                  placeholder="Ví dụ: Công nghệ thông tin"
                />
              </div>
            </div>
          )}

          {/* Chỉ hiển thị các field này khi chọn TEACHER */}
          {formData.type === 'TEACHER' && (
            <div className="form-row">
              <div className="form-group">
                <label htmlFor="department">Khoa/Phòng ban</label>
                <input
                  type="text"
                  id="department"
                  name="department"
                  value={formData.department}
                  onChange={handleChange}
                  placeholder="Ví dụ: Khoa Công nghệ thông tin"
                />
              </div>

              <div className="form-group">
                <label htmlFor="classManaging">Lớp quản lý</label>
                <input
                  type="text"
                  id="classManaging"
                  name="classManaging"
                  value={formData.classManaging}
                  onChange={handleChange}
                  placeholder="Ví dụ: CNTT2024A"
                />
              </div>
            </div>
          )}

          <button type="submit" className="auth-button" disabled={loading}>
            {loading ? 'Đang đăng ký...' : 'Đăng Ký'}
          </button>
        </form>

        <p className="auth-footer">
          Đã có tài khoản? <Link to="/app/login">Đăng nhập ngay</Link>
        </p>
      </div>
    </div>
  )
}

export default Register

