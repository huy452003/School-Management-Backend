import React, { useState, useEffect } from 'react'
import { useAuth } from '../../contexts/AuthContext'
import apiService from '../../services/apiService'
import { ageFromYyyyMmDd } from '../../utils/ageFromBirth'
import './Teachers.css'

const Teachers = () => {
  const { user } = useAuth()
  const [teachers, setTeachers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showEditModal, setShowEditModal] = useState(false)
  const [editingTeacher, setEditingTeacher] = useState(null)
  const [editForm, setEditForm] = useState(null)
  const [selectedIds, setSelectedIds] = useState([])
  // Pagination state
  const [currentPage, setCurrentPage] = useState(0)
  const [pageSize] = useState(10)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  // Filter state
  const [isFiltering, setIsFiltering] = useState(false)
  const [isFilterExpanded, setIsFilterExpanded] = useState(true) // Collapsible filter
  const [allFilteredTeachers, setAllFilteredTeachers] = useState([]) // Lưu tất cả kết quả filter
  const [filterForm, setFilterForm] = useState({
    id: '',
    firstName: '',
    lastName: '',
    age: '',
    gender: '',
    email: '',
    phoneNumber: '',
    classManaging: '',
    department: '',
  })

  useEffect(() => {
    let isMounted = true
    const load = async () => {
      if (isMounted) {
        await loadTeachers(0)
      }
    }
    load()
    return () => {
      isMounted = false
    }
  }, [])

  const loadTeachers = async (page = 0, forceRefresh = false) => {
    try {
      setLoading(true)
      setError('')
      setCurrentPage(page)
      
      // Use pagination API
      const response = await apiService.getTeachersPaged(page, pageSize, 'id', 'asc', forceRefresh)
      
      // Response structure: { status, message, modelName, errors, data: PagedResponseModel }
      const pagedData = response?.data || response
      
      if (pagedData) {
        const teachersList = pagedData.data || []
        setTeachers(teachersList)
        setTotalPages(pagedData.totalPages || 0)
        setTotalElements(pagedData.totalElements || 0)
      } else {
        setTeachers([])
        setTotalPages(0)
        setTotalElements(0)
        setError('Không có dữ liệu giáo viên')
      }
    } catch (err) {
      console.error('Load teachers error:', err)
      // Fallback to non-paginated API if pagination fails
      try {
        const response = await apiService.getTeachers(forceRefresh)
        if (response.data) {
          setTeachers(response.data)
          setTotalPages(Math.ceil(response.data.length / pageSize))
          setTotalElements(response.data.length)
        } else {
          setError('Không có dữ liệu giáo viên')
        }
      } catch (fallbackErr) {
        console.error('Fallback API also failed:', fallbackErr)
        setError(err.response?.data?.message || 'Không thể tải danh sách giáo viên')
      }
    } finally {
      setLoading(false)
    }
  }

  const handlePageChange = (newPage) => {
    if (newPage >= 0 && newPage < totalPages) {
      setCurrentPage(newPage)
      if (isFiltering && allFilteredTeachers.length > 0) {
        // Client-side pagination cho filter
        const startIndex = newPage * pageSize
        const endIndex = startIndex + pageSize
        setTeachers(allFilteredTeachers.slice(startIndex, endIndex))
      } else {
        // Server-side pagination
        loadTeachers(newPage)
      }
    }
  }

  const handleFilter = async () => {
    try {
      setLoading(true)
      setError('')
      setIsFiltering(true)
      setCurrentPage(0)
      
      // Chỉ gửi các field có giá trị
      const filters = {}
      if (filterForm.id) filters.id = parseInt(filterForm.id) || null
      if (filterForm.firstName) filters.firstName = filterForm.firstName
      if (filterForm.lastName) filters.lastName = filterForm.lastName
      if (filterForm.age) filters.age = parseInt(filterForm.age) || null
      if (filterForm.gender) filters.gender = filterForm.gender
      if (filterForm.email) filters.email = filterForm.email
      if (filterForm.phoneNumber) filters.phoneNumber = filterForm.phoneNumber
      if (filterForm.classManaging) filters.classManaging = filterForm.classManaging
      if (filterForm.department) filters.department = filterForm.department
      
      const response = await apiService.filterTeachers(filters)
      // Response structure: { status, message, modelName, errors, data: List<EntityModel> }
      // Axios interceptor returns response.data, so response is already unwrapped
      let teachersList = []
      if (Array.isArray(response)) {
        teachersList = response
      } else if (response?.data && Array.isArray(response.data)) {
        teachersList = response.data
      } else if (response?.data?.data && Array.isArray(response.data.data)) {
        teachersList = response.data.data
      }
      
      // Lưu tất cả kết quả filter
      setAllFilteredTeachers(teachersList)
      
      // Hiển thị trang đầu tiên
      const startIndex = 0
      const endIndex = pageSize
      setTeachers(teachersList.slice(startIndex, endIndex))
      
      // Tính toán pagination
      setTotalPages(Math.ceil(teachersList.length / pageSize))
      setTotalElements(teachersList.length)
      
      if (teachersList.length === 0) {
        setError('Không tìm thấy giáo viên nào phù hợp với điều kiện tìm kiếm')
      }
    } catch (err) {
      console.error('Filter teachers error:', err)
      setError(err.response?.data?.message || 'Không thể tìm kiếm giáo viên')
      setTeachers([])
      setAllFilteredTeachers([])
      setTotalPages(0)
      setTotalElements(0)
    } finally {
      setLoading(false)
    }
  }

  const handleResetFilter = () => {
    setFilterForm({
      id: '',
      firstName: '',
      lastName: '',
      age: '',
      gender: '',
      email: '',
      phoneNumber: '',
      classManaging: '',
      department: '',
    })
    setIsFiltering(false)
    setAllFilteredTeachers([])
    loadTeachers(0)
  }

  const handleEdit = (teacher) => {
    setEditingTeacher(teacher)
    const profileData = teacher.user?.profileData || {}
    // Format birth date từ dd-MM-yyyy sang yyyy-MM-dd cho input type="date"
    let birthDate = teacher.user.birth || ''
    if (birthDate && birthDate.includes('-')) {
      const parts = birthDate.split('-')
      if (parts.length === 3 && parts[0].length === 2) {
        birthDate = `${parts[2]}-${parts[1]}-${parts[0]}`
      }
    }
    
    setEditForm({
      user: {
        userId: teacher.user.userId,
        firstName: teacher.user.firstName || '',
        lastName: teacher.user.lastName || '',
        gender: teacher.user.gender || 'NAM',
        birth: birthDate,
        phoneNumber: teacher.user.phoneNumber || '',
        email: teacher.user.email || '',
      },
      profileData: {
        department: profileData.department || '',
        classManaging: profileData.classManaging || '',
      },
      // Thêm role, permissions, username, password, status cho ADMIN
      role: teacher.user?.role || 'TEACHER',
      permissions: teacher.user?.permissions 
        ? (Array.isArray(teacher.user.permissions) 
            ? [...teacher.user.permissions] 
            : Array.from(teacher.user.permissions))
        : [],
      username: teacher.user?.username || '',
      password: '', // Không load password, để trống
      status: teacher.user?.status || 'ENABLED',
    })
    setShowEditModal(true)
  }

  const handleCloseModal = () => {
    setShowEditModal(false)
    setEditingTeacher(null)
    setEditForm(null)
  }

  const handleSaveEdit = async () => {
    if (!editingTeacher) return

    try {
      // Format birth date từ yyyy-MM-dd sang dd-MM-yyyy
      const formattedData = { ...editForm }
      if (formattedData.user) {
        const { age: _omitAge, ...userRest } = formattedData.user
        formattedData.user = userRest
      }
      if (formattedData.user.birth) {
        const dateParts = formattedData.user.birth.split('-')
        if (dateParts.length === 3 && dateParts[0].length === 4) {
          formattedData.user.birth = `${dateParts[2]}-${dateParts[1]}-${dateParts[0]}`
        }
      }
      
      // Đảm bảo profileData được gửi đúng format
      if (formattedData.profileData) {
        formattedData.user = {
          ...formattedData.user,
          profileData: formattedData.profileData,
        }
        delete formattedData.profileData
      }
      
      // Update teacher
      await apiService.updateTeacher(editingTeacher.id, formattedData)
      
      // Nếu là ADMIN và có thay đổi role hoặc permissions, update riêng
      if (user?.role === 'ADMIN' && editForm.role && editForm.permissions && Array.isArray(editForm.permissions)) {
        const originalRole = editingTeacher.user?.role
        const originalPermissions = editingTeacher.user?.permissions 
          ? (Array.isArray(editingTeacher.user.permissions) 
              ? editingTeacher.user.permissions 
              : Array.from(editingTeacher.user.permissions))
          : []
        
        // Kiểm tra xem có thay đổi permissions, username, password, status không
        // Role không thể thay đổi, lấy từ user hiện tại
        const sortedOriginal = [...originalPermissions].sort()
        const sortedNew = [...editForm.permissions].sort()
        const permissionsChanged = JSON.stringify(sortedOriginal) !== JSON.stringify(sortedNew)
        
        // Kiểm tra xem có thay đổi username, password, status không
        const usernameChanged = editingTeacher.user?.username !== editForm.username
        const passwordChanged = editForm.password && editForm.password.trim() !== ''
        const statusChanged = editingTeacher.user?.status !== editForm.status
        
        if (permissionsChanged || usernameChanged || passwordChanged || statusChanged) {
          try {
            await apiService.adminUpdateUser(
              editingTeacher.user.userId,
              originalRole, // Giữ nguyên role hiện tại, không cho phép thay đổi
              editForm.permissions,
              editForm.username,
              editForm.password,
              editForm.status
            )
          } catch (adminUpdateErr) {
            console.error('Error updating admin fields:', adminUpdateErr)
            // Không throw error vì teacher info đã update thành công
            alert('Cập nhật thông tin thành công, nhưng cập nhật quản trị (role/permissions/username/password/status) thất bại. Vui lòng thử lại.')
            // Vẫn tiếp tục để refresh data
          }
        }
      }
      
      // Đóng modal trước
      handleCloseModal()
      alert('Cập nhật thành công!')
      
      // Refresh data sau khi đóng modal, nếu bị rate limit thì không hiển thị lỗi
      try {
        await loadTeachers(currentPage, true)
      } catch (refreshErr) {
        // Nếu refresh bị lỗi (có thể do rate limit), không hiển thị lỗi cho user
        // Không hiển thị alert vì update đã thành công
      }
    } catch (err) {
      console.error('Update teacher error:', err)
      console.error('Error response:', err.response?.data)
      // Chỉ hiển thị lỗi nếu là lỗi update, không phải lỗi refresh
      const errorMessage = err.response?.data?.message || 'Không thể cập nhật giáo viên'
      if (errorMessage.includes('quá nhiều yêu cầu') || errorMessage.includes('rate limit')) {
        // Nếu là rate limit, có thể update đã thành công, thử refresh sau 1 giây
        setTimeout(async () => {
          try {
            await loadTeachers(currentPage, true)
        } catch (refreshErr) {
          // Refresh failed silently
        }
        }, 1000)
        alert('Cập nhật có thể đã thành công. Vui lòng refresh trang để xem dữ liệu mới nhất.')
      } else {
        alert(errorMessage)
      }
    }
  }


  const toggleSelect = (userId) => {
    setSelectedIds((prev) =>
      prev.includes(userId)
        ? prev.filter((id) => id !== userId)
        : [...prev, userId]
    )
  }

  const toggleSelectAll = () => {
    const currentPageIds = teachers.map((t) => t.user.userId)
    const allSelected = currentPageIds.every(id => selectedIds.includes(id))
    
    if (allSelected) {
      // Deselect all on current page
      setSelectedIds(prev => prev.filter(id => !currentPageIds.includes(id)))
    } else {
      // Select all on current page
      setSelectedIds(prev => {
        const newIds = [...prev]
        currentPageIds.forEach(id => {
          if (!newIds.includes(id)) {
            newIds.push(id)
          }
        })
        return newIds
      })
    }
  }

  if (loading) {
    return <div className="loading">Đang tải...</div>
  }

  return (
    <div className="teachers-page">
      <div className="page-header">
        <h1>Quản Lý Giáo Viên</h1>
        <div className="header-actions">
          <button onClick={() => loadTeachers(currentPage, true)} className="refresh-btn">
            🔄 Làm mới
          </button>
        </div>
      </div>

      {error && <div className="error-message">{error}</div>}

      {/* Filter Section */}
      <div className={`filter-section ${!isFilterExpanded ? 'collapsed' : ''}`}>
        <div 
          className="filter-header"
          onClick={() => setIsFilterExpanded(!isFilterExpanded)}
        >
          <h3>🔍 Tìm kiếm giáo viên</h3>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flex: 1, justifyContent: 'flex-end' }}>
            <div className="filter-id-group" onClick={(e) => e.stopPropagation()}>
              <input
                type="number"
                value={filterForm.id}
                onChange={(e) => setFilterForm({ ...filterForm, id: e.target.value })}
                placeholder="Nhập ID"
              />
            </div>
            <div className="filter-actions" onClick={(e) => e.stopPropagation()}>
              <button onClick={handleFilter} className="filter-btn">
                🔍 Tìm kiếm
              </button>
              <button onClick={handleResetFilter} className="reset-filter-btn">
                🔄 Xóa bộ lọc
              </button>
            </div>
          </div>
        </div>
        <div className="filter-grid">
          <div className="filter-group">
            <label>Họ</label>
            <input
              type="text"
              value={filterForm.firstName}
              onChange={(e) => setFilterForm({ ...filterForm, firstName: e.target.value })}
              placeholder="Nhập họ"
            />
          </div>
          <div className="filter-group">
            <label>Tên</label>
            <input
              type="text"
              value={filterForm.lastName}
              onChange={(e) => setFilterForm({ ...filterForm, lastName: e.target.value })}
              placeholder="Nhập tên"
            />
          </div>
          <div className="filter-group">
            <label>Tuổi</label>
            <input
              type="number"
              value={filterForm.age}
              onChange={(e) => setFilterForm({ ...filterForm, age: e.target.value })}
              placeholder="Nhập tuổi"
              min="1"
              max="99"
            />
          </div>
          <div className="filter-group">
            <label>Giới tính</label>
            <select
              value={filterForm.gender}
              onChange={(e) => setFilterForm({ ...filterForm, gender: e.target.value })}
            >
              <option value="">Tất cả</option>
              <option value="NAM">Nam</option>
              <option value="NU">Nữ</option>
            </select>
          </div>
          <div className="filter-group">
            <label>Email</label>
            <input
              type="email"
              value={filterForm.email}
              onChange={(e) => setFilterForm({ ...filterForm, email: e.target.value })}
              placeholder="Nhập email"
            />
          </div>
          <div className="filter-group">
            <label>Số điện thoại</label>
            <input
              type="tel"
              value={filterForm.phoneNumber}
              onChange={(e) => setFilterForm({ ...filterForm, phoneNumber: e.target.value })}
              placeholder="Nhập SĐT"
            />
          </div>
          <div className="filter-group">
            <label>Lớp quản lý</label>
            <input
              type="text"
              value={filterForm.classManaging}
              onChange={(e) => setFilterForm({ ...filterForm, classManaging: e.target.value })}
              placeholder="Nhập lớp quản lý"
            />
          </div>
          <div className="filter-group">
            <label>Khoa/Phòng ban</label>
            <input
              type="text"
              value={filterForm.department}
              onChange={(e) => setFilterForm({ ...filterForm, department: e.target.value })}
              placeholder="Nhập khoa/phòng ban"
            />
          </div>
        </div>
      </div>

      <div className="table-container">
        <div className="table-scroll-wrapper">
          <table className="teachers-table">
            <colgroup>
              <col style={{ width: '3%' }} />
              <col style={{ width: '4%' }} />
              <col style={{ width: '11%' }} />
              <col style={{ width: '5%' }} />
              <col style={{ width: '7%' }} />
              <col style={{ width: '9%' }} />
              <col style={{ width: '9%' }} />
              <col style={{ width: '15%' }} />
              <col style={{ width: '10%' }} />
              <col style={{ width: '8%' }} />
              <col style={{ width: '10%' }} />
              <col style={{ width: '9%' }} />
            </colgroup>
            <thead>
              <tr>
                <th>
                  <input
                    type="checkbox"
                    checked={teachers.length > 0 && teachers.every(t => selectedIds.includes(t.user.userId))}
                    onChange={toggleSelectAll}
                  />
                </th>
                <th>ID</th>
                <th>Họ tên</th>
                <th>Tuổi</th>
                <th>Giới tính</th>
                <th>Ngày sinh</th>
                <th>SĐT</th>
                <th>Email</th>
                <th>Khoa</th>
                <th>Lớp quản lý</th>
                <th>Trạng thái</th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {teachers.length === 0 ? (
                <tr>
                  <td colSpan="12" className="empty-message">
                    Không có giáo viên nào
                  </td>
                </tr>
              ) : (
                teachers.map((teacher) => (
                  <tr key={teacher.id}>
                    <td>
                      <input
                        type="checkbox"
                        checked={selectedIds.includes(teacher.user.userId)}
                        onChange={() => toggleSelect(teacher.user.userId)}
                      />
                    </td>
                    <td>{teacher.id}</td>
                    <td title={`${teacher.user?.firstName || ''} ${teacher.user?.lastName || ''}`.trim() || '-'}>
                      {`${teacher.user?.firstName || ''} ${teacher.user?.lastName || ''}`.trim() || '-'}
                    </td>
                    <td>{teacher.user?.age || '-'}</td>
                    <td>{teacher.user?.gender === 'NAM' ? 'Nam' : teacher.user?.gender === 'NU' ? 'Nữ' : teacher.user?.gender || '-'}</td>
                    <td>{teacher.user?.birth || '-'}</td>
                    <td title={teacher.user.phoneNumber || '-'}>
                      {teacher.user.phoneNumber || '-'}
                    </td>
                    <td title={teacher.user.email || '-'}>
                      {teacher.user.email || '-'}
                    </td>
                    <td title={teacher.user?.profileData?.department || '-'}>
                      {teacher.user?.profileData?.department || '-'}
                    </td>
                    <td title={teacher.user?.profileData?.classManaging || '-'}>
                      {teacher.user?.profileData?.classManaging || '-'}
                    </td>
                    <td>
                      <span className={`status-badge status-${teacher.user.status}`}>
                        {teacher.user.status}
                      </span>
                    </td>
                    <td>
                      <button
                        onClick={() => handleEdit(teacher)}
                        className="edit-btn"
                        title="Sửa"
                      >
                        ✏️Sửa
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Pagination Controls */}
      {totalPages > 1 && (
        <div className="pagination-container">
          <div className="pagination-info">
            Trang {currentPage + 1} / {totalPages} ({totalElements} giáo viên)
          </div>
          <div className="pagination-buttons">
            <button
              className="pagination-btn"
              onClick={() => handlePageChange(0)}
              disabled={currentPage === 0}
            >
              ⏮️ Đầu
            </button>
            <button
              className="pagination-btn"
              onClick={() => handlePageChange(currentPage - 1)}
              disabled={currentPage === 0}
            >
              ◀️ Trước
            </button>
            {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
              let pageNum
              if (totalPages <= 5) {
                pageNum = i
              } else if (currentPage < 3) {
                pageNum = i
              } else if (currentPage > totalPages - 4) {
                pageNum = totalPages - 5 + i
              } else {
                pageNum = currentPage - 2 + i
              }
              return (
                <button
                  key={pageNum}
                  className={`pagination-btn ${currentPage === pageNum ? 'active' : ''}`}
                  onClick={() => handlePageChange(pageNum)}
                >
                  {pageNum + 1}
                </button>
              )
            })}
            <button
              className="pagination-btn"
              onClick={() => handlePageChange(currentPage + 1)}
              disabled={currentPage >= totalPages - 1}
            >
              Sau ▶️
            </button>
            <button
              className="pagination-btn"
              onClick={() => handlePageChange(totalPages - 1)}
              disabled={currentPage >= totalPages - 1}
            >
              Cuối ⏭️
            </button>
          </div>
        </div>
      )}

      {/* Edit Modal */}
      {showEditModal && editForm && (
        <div className="modal-overlay" onClick={handleCloseModal}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h2>✏️ Chỉnh Sửa Thông Tin Giáo Viên</h2>
              <button className="modal-close-btn" onClick={handleCloseModal}>✕</button>
            </div>
            
            <div className="modal-body">
              <div className="form-section">
                <h3>👤 Thông Tin Cá Nhân</h3>
                <div className="form-grid">
                  <div className="form-group">
                    <label>Họ *</label>
                    <input
                      type="text"
                      value={editForm.user.firstName}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          user: { ...editForm.user, firstName: e.target.value },
                        })
                      }
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label>Tên *</label>
                    <input
                      type="text"
                      value={editForm.user.lastName}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          user: { ...editForm.user, lastName: e.target.value },
                        })
                      }
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label>Tuổi</label>
                    <input
                      type="text"
                      readOnly
                      value={(() => {
                        const a = ageFromYyyyMmDd(editForm.user.birth)
                        return a != null ? String(a) : '—'
                      })()}
                      title="Tính từ ngày sinh"
                    />
                  </div>
                  <div className="form-group">
                    <label>Giới tính *</label>
                    <select
                      value={editForm.user.gender}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          user: { ...editForm.user, gender: e.target.value },
                        })
                      }
                      required
                    >
                      <option value="NAM">Nam</option>
                      <option value="NU">Nữ</option>
                    </select>
                  </div>
                  <div className="form-group">
                    <label>Ngày sinh *</label>
                    <input
                      type="date"
                      value={editForm.user.birth}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          user: { ...editForm.user, birth: e.target.value },
                        })
                      }
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label>Số điện thoại</label>
                    <input
                      type="tel"
                      value={editForm.user.phoneNumber}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          user: { ...editForm.user, phoneNumber: e.target.value },
                        })
                      }
                    />
                  </div>
                  <div className="form-group">
                    <label>Email</label>
                    <input
                      type="email"
                      value={editForm.user.email}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          user: { ...editForm.user, email: e.target.value },
                        })
                      }
                    />
                  </div>
                </div>
              </div>

              <div className="form-section">
                <h3>🏫 Thông Tin Công Tác</h3>
                <div className="form-grid">
                  <div className="form-group">
                    <label>Khoa/Phòng ban</label>
                    <input
                      type="text"
                      value={editForm.profileData.department}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          profileData: { ...editForm.profileData, department: e.target.value },
                        })
                      }
                      placeholder="Ví dụ: Khoa Công nghệ thông tin"
                    />
                  </div>
                  <div className="form-group">
                    <label>Lớp quản lý</label>
                    <input
                      type="text"
                      value={editForm.profileData.classManaging}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          profileData: { ...editForm.profileData, classManaging: e.target.value },
                        })
                      }
                      placeholder="Ví dụ: CNTT2024A"
                    />
                  </div>
                </div>
              </div>

              {/* Section Role, Permissions, Username, Password, Status - chỉ hiển thị cho ADMIN */}
              {user?.role === 'ADMIN' && (
                <div className="form-section">
                  <h3>👑 Quản trị tài khoản</h3>
                  <div className="form-grid">
                    <div className="form-group">
                      <label>Tên đăng nhập (Username) *</label>
                      <input
                        type="text"
                        value={editForm.username || ''}
                        onChange={(e) =>
                          setEditForm({
                            ...editForm,
                            username: e.target.value,
                          })
                        }
                        required
                        placeholder="Nhập username"
                      />
                    </div>
                    <div className="form-group">
                      <label>Mật khẩu (Password)</label>
                      <input
                        type="password"
                        value={editForm.password || ''}
                        onChange={(e) =>
                          setEditForm({
                            ...editForm,
                            password: e.target.value,
                          })
                        }
                        placeholder="Để trống nếu không đổi mật khẩu"
                      />
                    </div>
                    <div className="form-group form-group-full">
                      <label>Quyền hạn (Permissions) Và Trạng Thái (Status) *</label>
                      <div className="permissions-checkbox-group">
                        <div className="permission-checkbox-item">
                          <div style={{ fontWeight: '600', marginBottom: '0.5rem', color: '#495057', fontSize: '0.9rem' }}>STUDENT</div>
                          {['STUDENT_READ', 'STUDENT_WRITE', 'STUDENT_DELETE'].map((perm) => (
                            <label key={perm} className="permission-checkbox">
                              <input
                                type="checkbox"
                                checked={editForm.permissions && Array.isArray(editForm.permissions) && editForm.permissions.includes(perm)}
                                onChange={(e) => {
                                  const currentPermissions = Array.isArray(editForm.permissions) ? editForm.permissions : []
                                  if (e.target.checked) {
                                    setEditForm({
                                      ...editForm,
                                      permissions: [...currentPermissions, perm]
                                    })
                                  } else {
                                    setEditForm({
                                      ...editForm,
                                      permissions: currentPermissions.filter(p => p !== perm)
                                    })
                                  }
                                }}
                              />
                              <span>{perm.replace('STUDENT_', '').replace('_', ' ')}</span>
                            </label>
                          ))}
                        </div>
                        <div className="permission-checkbox-item">
                          <div style={{ fontWeight: '600', marginBottom: '0.5rem', color: '#495057', fontSize: '0.9rem' }}>TEACHER</div>
                          {['TEACHER_READ', 'TEACHER_WRITE', 'TEACHER_DELETE'].map((perm) => (
                            <label key={perm} className="permission-checkbox">
                              <input
                                type="checkbox"
                                checked={editForm.permissions && Array.isArray(editForm.permissions) && editForm.permissions.includes(perm)}
                                onChange={(e) => {
                                  const currentPermissions = Array.isArray(editForm.permissions) ? editForm.permissions : []
                                  if (e.target.checked) {
                                    setEditForm({
                                      ...editForm,
                                      permissions: [...currentPermissions, perm]
                                    })
                                  } else {
                                    setEditForm({
                                      ...editForm,
                                      permissions: currentPermissions.filter(p => p !== perm)
                                    })
                                  }
                                }}
                              />
                              <span>{perm.replace('TEACHER_', '').replace('_', ' ')}</span>
                            </label>
                          ))}
                        </div>
                        <div className="permission-checkbox-item">
                          <div style={{ fontWeight: '600', marginBottom: '0.5rem', color: '#495057', fontSize: '0.9rem' }}>STATUS</div>
                          {[
                            { value: 'ENABLED', label: 'Đang hoạt động' },
                            { value: 'DISABLED', label: 'Đã vô hiệu hóa' },
                            { value: 'FAILED', label: 'Thất bại' }
                          ].map((status) => (
                            <label key={status.value} className="permission-checkbox">
                              <input
                                type="radio"
                                name="status"
                                value={status.value}
                                checked={editForm.status === status.value}
                                onChange={(e) =>
                                  setEditForm({
                                    ...editForm,
                                    status: e.target.value,
                                  })
                                }
                                required
                              />
                              <span>{status.label}</span>
                            </label>
                          ))}
                        </div>
                      </div>
                      {(!editForm.permissions || !Array.isArray(editForm.permissions) || editForm.permissions.length === 0) && (
                        <div className="error-message" style={{ marginTop: '0.5rem', fontSize: '0.9rem' }}>
                          Vui lòng chọn ít nhất một quyền hạn
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </div>

            <div className="modal-footer">
              <button onClick={handleCloseModal} className="cancel-btn">
                ❌ Hủy
              </button>
              <button onClick={handleSaveEdit} className="save-btn">
                💾 Lưu Thay Đổi
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default Teachers

