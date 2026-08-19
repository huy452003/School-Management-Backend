import React, { useState, useEffect } from 'react'
import { useAuth } from '../../contexts/AuthContext'
import apiService from '../../services/apiService'
import { ageFromYyyyMmDd } from '../../utils/ageFromBirth'
import './Students.css'

const Students = () => {
  const { user } = useAuth()
  const [students, setStudents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showEditModal, setShowEditModal] = useState(false)
  const [editingStudent, setEditingStudent] = useState(null)
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
  const [allFilteredStudents, setAllFilteredStudents] = useState([]) // Lưu tất cả kết quả filter
  const [teacherClassManaging, setTeacherClassManaging] = useState(null) // Lưu classManaging của teacher
  const [filterForm, setFilterForm] = useState({
    id: '',
    firstName: '',
    lastName: '',
    age: '',
    gender: '',
    email: '',
    phoneNumber: '',
    score: '',
    schoolClass: '',
    major: '',
    graduate: '',
  })

  useEffect(() => {
    loadStudents(0)
    // Nếu là teacher, load thông tin classManaging
    if (user?.role === 'TEACHER' && user?.userId) {
      loadTeacherClassManaging()
    }
  }, [user])

  const loadTeacherClassManaging = async () => {
    try {
      const response = await apiService.getTeacherByUserId(user.userId, false)
      const teacherInfo = response?.data || response
      const profileData = teacherInfo?.user?.profileData || {}
      const classManaging = profileData.classManaging
      
      if (classManaging) {
        // classManaging có thể là string hoặc array
        let classList = []
        if (typeof classManaging === 'string') {
          classList = classManaging.split(',').map(c => c.trim()).filter(c => c)
        } else if (Array.isArray(classManaging)) {
          classList = classManaging
        }
        
        if (classList.length > 0) {
          // Lưu lớp đầu tiên hoặc tất cả các lớp (tùy logic)
          setTeacherClassManaging(classList[0]) // Hoặc có thể lưu tất cả
        }
      }
    } catch (err) {
      console.error('Error loading teacher classManaging:', err)
    }
  }

  const loadStudents = async (page = 0, forceRefresh = false) => {
    try {
      setLoading(true)
      setError('')
      setCurrentPage(page)
      
      // Use pagination API
      const response = await apiService.getStudentsPaged(page, pageSize, 'id', 'asc', null, forceRefresh)
      
      // Axios interceptor returns response.data, so response is already unwrapped
      // Response structure from backend: Response<PagedResponseModel<EntityModel>>
      // After axios interceptor: { status, message, modelName, errors, data: PagedResponseModel }
      // So response.data is the PagedResponseModel { data: [...], page, size, totalPages, totalElements, ... }
      const pagedData = response?.data || response
      
      if (pagedData) {
        const studentsList = pagedData.data || []
        setStudents(studentsList)
        setTotalPages(pagedData.totalPages || 0)
        setTotalElements(pagedData.totalElements || 0)
      } else {
        setStudents([])
        setTotalPages(0)
        setTotalElements(0)
        setError('Không có dữ liệu sinh viên')
      }
    } catch (err) {
      console.error('Load students error:', err)
      // Fallback to non-paginated API if pagination fails
      try {
        const response = await apiService.getStudents(forceRefresh)
        if (response.data) {
          setStudents(response.data)
          setTotalPages(Math.ceil(response.data.length / pageSize))
          setTotalElements(response.data.length)
        } else {
          setError('Không có dữ liệu sinh viên')
        }
      } catch (fallbackErr) {
        console.error('Fallback API also failed:', fallbackErr)
        setError(err.response?.data?.message || 'Không thể tải danh sách sinh viên')
      }
    } finally {
      setLoading(false)
    }
  }

  const handlePageChange = (newPage) => {
    if (newPage >= 0 && newPage < totalPages) {
      setCurrentPage(newPage)
      if (isFiltering && allFilteredStudents.length > 0) {
        // Client-side pagination cho filter
        const startIndex = newPage * pageSize
        const endIndex = startIndex + pageSize
        setStudents(allFilteredStudents.slice(startIndex, endIndex))
      } else {
        // Server-side pagination
        loadStudents(newPage)
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
      if (filterForm.score) filters.score = parseFloat(filterForm.score) || null
      // Nếu là teacher, luôn filter theo classManaging của teacher
      if (user?.role === 'TEACHER' && teacherClassManaging) {
        filters.schoolClass = teacherClassManaging
      } else if (filterForm.schoolClass) {
        // Chỉ set schoolClass nếu không phải teacher
        filters.schoolClass = filterForm.schoolClass
      }
      
      if (filterForm.major) filters.major = filterForm.major
      if (filterForm.graduate !== '') {
        filters.graduate = filterForm.graduate === 'true'
      }
      
      const response = await apiService.filterStudents(filters)
      // Response structure: { status, message, modelName, errors, data: List<EntityModel> }
      // Axios interceptor returns response.data, so response is already unwrapped
      let studentsList = []
      if (Array.isArray(response)) {
        studentsList = response
      } else if (response?.data && Array.isArray(response.data)) {
        studentsList = response.data
      } else if (response?.data?.data && Array.isArray(response.data.data)) {
        studentsList = response.data.data
      }
      
      // Nếu là teacher, lọc thêm để đảm bảo chỉ hiển thị students có classManaging khớp
      if (user?.role === 'TEACHER' && teacherClassManaging) {
        studentsList = studentsList.filter(student => {
          const studentClass = student?.user?.profileData?.schoolClass
          return studentClass === teacherClassManaging
        })
      }
      
      // Lưu tất cả kết quả filter
      setAllFilteredStudents(studentsList)
      
      // Hiển thị trang đầu tiên
      const startIndex = 0
      const endIndex = pageSize
      setStudents(studentsList.slice(startIndex, endIndex))
      
      // Tính toán pagination
      setTotalPages(Math.ceil(studentsList.length / pageSize))
      setTotalElements(studentsList.length)
      
      if (studentsList.length === 0) {
        setError('Không tìm thấy sinh viên nào phù hợp với điều kiện tìm kiếm')
      }
    } catch (err) {
      console.error('Filter students error:', err)
      setError(err.response?.data?.message || 'Không thể tìm kiếm sinh viên')
      setStudents([])
      setAllFilteredStudents([])
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
      score: '',
      schoolClass: '',
      major: '',
      graduate: '',
    })
    setIsFiltering(false)
    setAllFilteredStudents([])
    loadStudents(0)
  }

  const handleEdit = (student) => {
    setEditingStudent(student)
    const profileData = student.user?.profileData || {}
    // Format birth date từ dd-MM-yyyy sang yyyy-MM-dd cho input type="date"
    let birthDate = student.user.birth || ''
    if (birthDate && birthDate.includes('-')) {
      const parts = birthDate.split('-')
      if (parts.length === 3 && parts[0].length === 2) {
        birthDate = `${parts[2]}-${parts[1]}-${parts[0]}`
      }
    }
    
    setEditForm({
      user: {
        userId: student.user.userId,
        firstName: student.user.firstName || '',
        lastName: student.user.lastName || '',
        gender: student.user.gender || 'NAM',
        birth: birthDate,
        phoneNumber: student.user.phoneNumber || '',
        email: student.user.email || '',
      },
      profileData: {
        schoolClass: profileData.schoolClass || '',
        major: profileData.major || '',
        graduate: profileData.graduate || false,
        score: profileData.score !== null && profileData.score !== undefined ? profileData.score : '',
      },
      // Thêm role, permissions, username, password, status cho ADMIN
      role: student.user?.role || 'STUDENT',
      permissions: student.user?.permissions ? Array.from(student.user.permissions) : [],
      username: student.user?.username || '',
      password: '', // Không load password, để trống
      status: student.user?.status || 'ENABLED',
    })
    setShowEditModal(true)
  }

  const handleCloseModal = () => {
    setShowEditModal(false)
    setEditingStudent(null)
    setEditForm(null)
  }

  const handleSaveEdit = async () => {
    if (!editingStudent) return
    
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
      
      // Convert score từ string sang number hoặc null
      if (formattedData.profileData.score === '' || formattedData.profileData.score === null) {
        formattedData.profileData.score = null
      } else {
        formattedData.profileData.score = parseFloat(formattedData.profileData.score)
      }
      
      // Đảm bảo profileData được gửi đúng format
      if (formattedData.profileData) {
        formattedData.user = {
          ...formattedData.user,
          profileData: formattedData.profileData,
        }
        delete formattedData.profileData
      }
      
      // Update student
      await apiService.updateStudent(editingStudent.id, formattedData)
      
      // Nếu là ADMIN và có thay đổi role hoặc permissions, update riêng
      if (user?.role === 'ADMIN' && editForm.role && editForm.permissions) {
        const originalRole = editingStudent.user?.role
        const originalPermissions = editingStudent.user?.permissions 
          ? (Array.isArray(editingStudent.user.permissions) 
              ? editingStudent.user.permissions 
              : Array.from(editingStudent.user.permissions))
          : []
        
        // Kiểm tra xem có thay đổi permissions, username, password, status không
        // Role không thể thay đổi, lấy từ user hiện tại
        const sortedOriginal = [...originalPermissions].sort()
        const sortedNew = [...editForm.permissions].sort()
        const permissionsChanged = JSON.stringify(sortedOriginal) !== JSON.stringify(sortedNew)
        const usernameChanged = editingStudent.user?.username !== editForm.username
        const passwordChanged = editForm.password && editForm.password.trim() !== ''
        const statusChanged = editingStudent.user?.status !== editForm.status
        
        if (permissionsChanged || usernameChanged || passwordChanged || statusChanged) {
          try {
            await apiService.adminUpdateUser(
              editingStudent.user.userId,
              originalRole, // Giữ nguyên role hiện tại, không cho phép thay đổi
              editForm.permissions,
              editForm.username,
              editForm.password,
              editForm.status
            )
          } catch (adminUpdateErr) {
            console.error('Error updating admin fields:', adminUpdateErr)
            // Không throw error vì student info đã update thành công
            alert('Cập nhật thông tin thành công, nhưng cập nhật quản trị (role/permissions/username/password/status) thất bại. Vui lòng thử lại.')
          }
        }
      }
      
      // Đóng modal trước
      handleCloseModal()
      alert('Cập nhật thành công!')
      
      // Refresh data sau khi đóng modal, nếu bị rate limit thì không hiển thị lỗi
      try {
        await loadStudents(currentPage, true)
      } catch (refreshErr) {
        // Nếu refresh bị lỗi (có thể do rate limit), chỉ log warning, không hiển thị lỗi cho user
        // Refresh failed silently
        // Không hiển thị alert vì update đã thành công
      }
    } catch (err) {
      console.error('Update student error:', err)
      // Chỉ hiển thị lỗi nếu là lỗi update, không phải lỗi refresh
      const errorMessage = err.response?.data?.message || 'Không thể cập nhật sinh viên'
      if (errorMessage.includes('quá nhiều yêu cầu') || errorMessage.includes('rate limit')) {
        // Nếu là rate limit, có thể update đã thành công, thử refresh sau 1 giây
        setTimeout(async () => {
          try {
            await loadStudents(currentPage, true)
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
    const currentPageIds = students.map((s) => s.user.userId)
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
    <div className="students-page">
      <div className="page-header">
        <h1>Quản Lý Sinh Viên</h1>
        <div className="header-actions">
          <button onClick={() => loadStudents(currentPage, true)} className="refresh-btn">
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
          <h3>🔍 Tìm kiếm sinh viên</h3>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flex: 1, justifyContent: 'flex-end' }}>
            <div className="filter-id-group" onClick={(e) => e.stopPropagation()}>
              <input
                type="number"
                value={filterForm.id}
                onChange={(e) => setFilterForm({ ...filterForm, id: e.target.value })}
                placeholder="Nhập ID"
              />
            </div>
            {user?.role === 'TEACHER' && teacherClassManaging && (
              <div className="filter-info" onClick={(e) => e.stopPropagation()}>
                <span className="info-badge">Lớp quản lý: {teacherClassManaging}</span>
              </div>
            )}
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
            <label>Điểm số</label>
            <input
              type="number"
              value={filterForm.score}
              onChange={(e) => setFilterForm({ ...filterForm, score: e.target.value })}
              placeholder="Nhập điểm"
              min="0"
              max="10"
              step="0.1"
            />
          </div>
          <div className="filter-group">
            <label>Lớp</label>
            <input
              type="text"
              value={filterForm.schoolClass}
              onChange={(e) => setFilterForm({ ...filterForm, schoolClass: e.target.value })}
              placeholder={user?.role === 'TEACHER' ? `Mặc định: ${teacherClassManaging || ''}` : 'Nhập lớp'}
              disabled={user?.role === 'TEACHER'} // Teacher không thể thay đổi lớp
            />
          </div>
          <div className="filter-group">
            <label>Chuyên ngành</label>
            <input
              type="text"
              value={filterForm.major}
              onChange={(e) => setFilterForm({ ...filterForm, major: e.target.value })}
              placeholder="Nhập chuyên ngành"
            />
          </div>
          <div className="filter-group">
            <label>Tình trạng tốt nghiệp</label>
            <select
              value={filterForm.graduate}
              onChange={(e) => setFilterForm({ ...filterForm, graduate: e.target.value })}
            >
              <option value="">Tất cả</option>
              <option value="false">Chưa tốt nghiệp</option>
              <option value="true">Đã tốt nghiệp</option>
            </select>
          </div>
        </div>
      </div>

      <div className="table-container">
        <div className="table-scroll-wrapper">
          <table className="students-table">
            <colgroup>
              <col style={{ width: '3%' }} />
              <col style={{ width: '4%' }} />
              <col style={{ width: '12%' }} />
              <col style={{ width: '5%' }} />
              <col style={{ width: '6%' }} />
              <col style={{ width: '9%' }} />
              <col style={{ width: '10%' }} />
              <col style={{ width: '17%' }} />
              <col style={{ width: '11%' }} />
              <col style={{ width: '11%' }} />
              <col style={{ width: '12%' }} />
            </colgroup>
            <thead>
              <tr>
                <th>
                  <input
                    type="checkbox"
                    checked={students.length > 0 && students.every(s => selectedIds.includes(s.user.userId))}
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
                <th>Tốt nghiệp</th>
                <th>Trạng thái</th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {students.length === 0 ? (
                <tr>
                  <td colSpan="11" className="empty-message">
                    Không có sinh viên nào
                  </td>
                </tr>
              ) : (
                students.map((student) => (
                  <tr key={student.id}>
                    <td>
                      <input
                        type="checkbox"
                        checked={selectedIds.includes(student.user.userId)}
                        onChange={() => toggleSelect(student.user.userId)}
                      />
                    </td>
                    <td>{student.id}</td>
                    <td title={`${student.user?.firstName || ''} ${student.user?.lastName || ''}`.trim() || '-'}>
                      {`${student.user?.firstName || ''} ${student.user?.lastName || ''}`.trim() || '-'}
                    </td>
                    <td>{student.user?.age || '-'}</td>
                    <td>{student.user?.gender === 'NAM' ? 'Nam' : student.user?.gender === 'NU' ? 'Nữ' : student.user?.gender || '-'}</td>
                    <td>{student.user?.birth || '-'}</td>
                    <td title={student.user.phoneNumber || '-'}>
                      {student.user.phoneNumber || '-'}
                    </td>
                    <td title={student.user.email || '-'}>
                      {student.user.email || '-'}
                    </td>
                    <td>
                      <span 
                        className={`status-badge ${
                          student.user?.profileData?.graduate === true || student.user?.profileData?.graduate === 'true' 
                            ? 'graduated' 
                            : 'studying'
                        }`}
                        title={student.user?.profileData?.graduate === true || student.user?.profileData?.graduate === 'true' 
                          ? 'Đã Tốt Nghiệp' 
                          : 'Chưa tốt nghiệp'}
                      >
                        {student.user?.profileData?.graduate === true || student.user?.profileData?.graduate === 'true' 
                          ? 'Tốt nghiệp' 
                          : 'Chưa'}
                      </span>
                    </td>
                    <td>
                      <span 
                        className={`status-badge status-${student.user.status}`}
                        title={student.user.status}
                      >
                        {student.user.status}
                      </span>
                    </td>
                    <td>
                      <button
                        onClick={() => handleEdit(student)}
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
            Trang {currentPage + 1} / {totalPages} ({totalElements} sinh viên)
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
              <h2>✏️ Chỉnh Sửa Thông Tin Sinh Viên</h2>
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
                <h3>🎓 Thông Tin Học Tập</h3>
                <div className="form-grid">
                  <div className="form-group">
                    <label>Lớp</label>
                    <input
                      type="text"
                      value={editForm.profileData.schoolClass}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          profileData: { ...editForm.profileData, schoolClass: e.target.value },
                        })
                      }
                      placeholder="Ví dụ: CNTT2024A"
                    />
                  </div>
                  <div className="form-group">
                    <label>Chuyên ngành</label>
                    <input
                      type="text"
                      value={editForm.profileData.major}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          profileData: { ...editForm.profileData, major: e.target.value },
                        })
                      }
                      placeholder="Ví dụ: Công nghệ thông tin"
                    />
                  </div>
                  <div className="form-group">
                    <label>Điểm số</label>
                    <input
                      type="number"
                      value={editForm.profileData.score || ''}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          profileData: { 
                            ...editForm.profileData, 
                            score: e.target.value ? e.target.value : '' 
                          },
                        })
                      }
                      min="0"
                      max="10"
                      step="0.1"
                      placeholder="0.0 - 10.0"
                    />
                  </div>
                  <div className="form-group">
                    <label>Tình trạng tốt nghiệp</label>
                    <select
                      value={editForm.profileData.graduate === true || editForm.profileData.graduate === 'true' ? 'true' : 'false'}
                      onChange={(e) =>
                        setEditForm({
                          ...editForm,
                          profileData: { 
                            ...editForm.profileData, 
                            graduate: e.target.value === 'true' 
                          },
                        })
                      }
                    >
                      <option value="false">Chưa</option>
                      <option value="true">Đã Tốt Nghiệp</option>
                    </select>
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

export default Students

