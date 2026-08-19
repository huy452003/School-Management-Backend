import React, { createContext, useState, useContext, useEffect, useCallback } from 'react'
import apiService from '../services/apiService'
import API_CONFIG from '../config/api'
import { formatApiError } from '../utils/formatApiError'

const AuthContext = createContext(null)

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)

  const validateToken = useCallback(async () => {
    try {
      const response = await apiService.validateToken()
      if (response.data) {
        setUser(response.data)
        return response.data
      }
      return null
    } catch (error) {
      console.error('Token validation failed:', error)
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      return null
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    // Kiểm tra token khi app load
    const token = localStorage.getItem('accessToken')
    if (token) {
      validateToken()
    } else {
      setLoading(false)
    }
  }, [validateToken])

  const login = async (username, password) => {
    try {
      const response = await apiService.login(username, password)
      // Response structure: { status, message, modelName, errors, data }
      // data chứa SecurityResponse với accessToken, refreshToken
      if (response.data) {
        const securityResponse = response.data
        // SecurityResponse KHÔNG có firstName/lastName, cần gọi validateToken để lấy UserDto
        if (securityResponse.accessToken) {
          // Token đã được lưu trong apiService.login
          // Validate token để lấy đầy đủ user info (UserDto có firstName, lastName)
          // Đợi một chút để đảm bảo token đã được lưu vào localStorage
          await new Promise(resolve => setTimeout(resolve, 100))
          
          try {
            const userResponse = await apiService.validateToken()
            if (userResponse.data) {
              const userData = userResponse.data
              // Set user state
              setUser(userData)
              console.log('User info loaded:', userData)
              
              // Đợi để đảm bảo state được cập nhật
              // Sử dụng một cách đơn giản hơn - đợi đủ lâu
              await new Promise(resolve => setTimeout(resolve, 500))
              
              return { success: true, data: securityResponse, user: userData }
            } else {
              console.warn('validateToken returned no data')
              // Fallback: tạo user object từ SecurityResponse
              const fallbackUser = {
                username: securityResponse.username,
                userId: securityResponse.userId,
                role: securityResponse.role,
                status: securityResponse.status,
              }
              setUser(fallbackUser)
              return { success: true, data: securityResponse, user: fallbackUser }
            }
          } catch (validateError) {
            console.error('Token validation error:', validateError)
            // Fallback: tạo user object từ SecurityResponse nếu validateToken thất bại
            const fallbackUser = {
              username: securityResponse.username,
              userId: securityResponse.userId,
              role: securityResponse.role,
              status: securityResponse.status,
            }
            setUser(fallbackUser)
            return { success: true, data: securityResponse, user: fallbackUser }
          }
        }
        return { success: true, data: securityResponse }
      }
      return { success: false, error: 'Login failed - No data received' }
    } catch (error) {
      console.error('Login error:', error)
      // Xử lý các loại lỗi khác nhau
      let errorMessage = 'Đăng nhập thất bại'
      
      if (error.response) {
        // Server trả về lỗi (Response.errors có chi tiết từng field)
        errorMessage = formatApiError(error, `Lỗi ${error.response.status}: ${error.response.statusText}`)
      } else if (error.request) {
        // Request được gửi nhưng không nhận được response (CORS, network error)
        const backendUrl = API_CONFIG?.SECURITY_BASE_URL || 'http://localhost:8083'
        errorMessage = `Không thể kết nối đến server tại ${backendUrl}`
      } else {
        // Lỗi khác
        errorMessage = error.message || 'Đăng nhập thất bại'
      }
      
      return {
        success: false,
        error: errorMessage,
      }
    }
  }

  const register = async (userData) => {
    try {
      const response = await apiService.register(userData)
      if (response.data) {
        setUser(response.data)
        return { success: true, data: response.data }
      }
      return { success: false, error: 'Register failed' }
    } catch (error) {
      return {
        success: false,
        error: formatApiError(error, 'Đăng ký thất bại'),
      }
    }
  }

  const logout = async () => {
    await apiService.logout()
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, loading, login, register, logout, validateToken }}>
      {children}
    </AuthContext.Provider>
  )
}

export const useAuth = () => {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return context
}

