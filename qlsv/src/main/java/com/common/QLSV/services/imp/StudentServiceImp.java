package com.common.QLSV.services.imp;

import com.common.QLSV.entities.StudentEntity;
import com.common.QLSV.repositories.StudentRepo;
import com.common.QLSV.services.StudentService;
import com.logging.services.LoggingService;
import com.logging.models.LogContext;
import com.handle_exceptions.NotFoundExceptionHandle;
import com.handle_exceptions.ServiceUnavailableExceptionHandle;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Isolation;

import com.model_shared.enums.Role;
import com.model_shared.models.user.EntityModel;
import com.model_shared.models.user.UpdateEntityModel;
import com.model_shared.models.user.UserDto;
import com.model_shared.models.pages.PagedRequestModel;
import com.model_shared.models.pages.PagedResponseModel;
import com.model_shared.enums.Gender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import com.security_shared.services.SecurityService;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.retry.annotation.Retryable;
import org.springframework.dao.OptimisticLockingFailureException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;

@Service
public class StudentServiceImp implements StudentService {
    @Autowired
    StudentRepo studentRepo;
    @Autowired
    ModelMapper modelMapper;
    @Autowired
    RedisTemplate<String, Object> redisTemplate;
    @Autowired
    LoggingService loggingService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    RestTemplate restTemplate;
    @Autowired
    SecurityService securityService;
    @Value("${security.base-url:http://localhost:8083}")
    private String securityBaseUrl;

    private static final String STUDENT_CACHE_KEY = "students:all";
    private static final String STUDENT_BY_USER_ID_CACHE_PREFIX = "students:user:";
    private static final String STUDENT_BY_CLASS_CACHE_PREFIX = "students:class:";
    private static final long CACHE_TTL_MINUTES = 10; // TTL cho cache (10 phút)

    private LogContext getLogContext(String methodName) {
            return LogContext.builder()
                    .module("qlsv")
                    .className(this.getClass().getSimpleName())
                    .methodName(methodName)
                    .build();
    }

    @Override
    public void clearCache() {
        LogContext logContext = getLogContext("clearCache");
        
        // Xóa cache chính
        redisTemplate.delete(STUDENT_CACHE_KEY);
        
        // Xóa tất cả cache theo pattern
        Set<String> userKeys = redisTemplate.keys(STUDENT_BY_USER_ID_CACHE_PREFIX + "*");
        if (userKeys != null && !userKeys.isEmpty()) {
            redisTemplate.delete(userKeys);
        }
        
        Set<String> classKeys = redisTemplate.keys(STUDENT_BY_CLASS_CACHE_PREFIX + "*");
        if (classKeys != null && !classKeys.isEmpty()) {
            redisTemplate.delete(classKeys);
        }
        
        Set<String> pagedKeys = redisTemplate.keys("students:paged:*");
        if (pagedKeys != null && !pagedKeys.isEmpty()) {
            redisTemplate.delete(pagedKeys);
        }
        
        loggingService.logInfo("Cleared all students cache (all, user:*, class:*, paged:*)", logContext);
    }

    @Override
    @CircuitBreaker(name = "qlsv-service", fallbackMethod = "getsFallback")
    public List<EntityModel> gets() {
        LogContext logContext = getLogContext("gets");
        
        // lấy data từ cache
        Object cached = redisTemplate.opsForValue().get(STUDENT_CACHE_KEY);
        if (cached != null) {
            // Convert từ List<LinkedHashMap> (JSON deserialized) sang List<EntityModel>
            List<EntityModel> cachedStudents = objectMapper.convertValue(
                cached, new TypeReference<List<EntityModel>>() {}
            );
            loggingService.logInfo("Get data from Redis cache : " + STUDENT_CACHE_KEY, logContext);
            return cachedStudents;
        }
        loggingService.logWarn("Not found cache, Query DB", logContext);

        List<StudentEntity> studentEntities = studentRepo.findAll();
        if (studentEntities.isEmpty()) {
            throw new NotFoundExceptionHandle("", Collections.emptyList(), "StudentModel");
        }

        // tạo list userIds để gửi qua security service
        List<Integer> userIds = studentEntities.stream()
                .map(StudentEntity::getUserId)
                .distinct()
                .toList();
        Map<Integer, UserDto> userIdFound = securityService.getUsersByIds(userIds);

        List<EntityModel> studentModels = new ArrayList<>();
        for (StudentEntity studentEntity : studentEntities) {
            UserDto userDto = userIdFound.get(studentEntity.getUserId());
            
            if (userDto != null) {
                if (userDto.getProfileData() == null) {
                    userDto.setProfileData(new HashMap<>());
                }
                userDto.getProfileData().put("graduate", studentEntity.getGraduate());
                userDto.getProfileData().put("score", studentEntity.getScore());
                userDto.getProfileData().put("schoolClass", studentEntity.getSchoolClass());
                userDto.getProfileData().put("major", studentEntity.getMajor());
                
                EntityModel studentModel = new EntityModel();
                studentModel.setId(studentEntity.getId());
                studentModel.setUser(userDto);
                
                studentModels.add(studentModel);
                loggingService.logStudentOperation("GET", studentEntity.getId(), logContext);
            }
        }

        // lưu data vào cache với TTL là 10 phút
        redisTemplate.opsForValue().set(
            STUDENT_CACHE_KEY,
            studentModels,
            CACHE_TTL_MINUTES,
            TimeUnit.MINUTES
        );
        loggingService.logInfo("Save cache to Redis : " + STUDENT_CACHE_KEY, logContext);

        loggingService.logInfo("Gets Students Successfully", logContext);
        return studentModels;
    }

    @Override
    @CircuitBreaker(name = "qlsv-service", fallbackMethod = "getByUserIdFallback")
    public EntityModel getByUserId(Integer userId) {
        LogContext logContext = getLogContext("getByUserId");

        // lấy data từ cache
        String cacheKey = STUDENT_BY_USER_ID_CACHE_PREFIX + userId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            // Convert từ LinkedHashMap (JSON deserialized) sang EntityModel
            EntityModel cachedStudent = objectMapper.convertValue(
                cached, EntityModel.class
            );
            loggingService.logInfo("Get student by userId from cache: " + cacheKey, logContext);
            return cachedStudent;
        }
        loggingService.logWarn("Not found cache for userId: " + userId + ", query DB", logContext);

        StudentEntity studentEntity = studentRepo.findByUserId(userId).orElseThrow(
            () -> new NotFoundExceptionHandle("", List.of(String.valueOf(userId))
            , "StudentModel"));
            
        UserDto userDto = securityService.getUsersByIds(List.of(userId)).get(userId);
        
        if (userDto != null) {
            if (userDto.getProfileData() == null) {
                userDto.setProfileData(new HashMap<>());
            }
            // Put tất cả các field từ StudentEntity vào profileData
            userDto.getProfileData().put("graduate", studentEntity.getGraduate());
            userDto.getProfileData().put("score", studentEntity.getScore());
            userDto.getProfileData().put("schoolClass", studentEntity.getSchoolClass());
            userDto.getProfileData().put("major", studentEntity.getMajor());
        }
        
        EntityModel studentModel = new EntityModel();
        studentModel.setId(studentEntity.getId());  
        studentModel.setUser(userDto);
        
        // lưu data vào cache với TTL là 10 phút
        redisTemplate.opsForValue().set(
            cacheKey, 
            studentModel, 
            CACHE_TTL_MINUTES, 
            TimeUnit.MINUTES
        );
        loggingService.logInfo("Save student by userId cache to Redis : " + cacheKey, logContext);
        
        loggingService.logInfo("Get student by userId: " + userId + " Successfully", logContext);
        return studentModel;
    }

    @Override
    @CircuitBreaker(name = "qlsv-service", fallbackMethod = "getBySchoolClassFallback")
    public List<EntityModel> getBySchoolClass(String schoolClass) {
        LogContext logContext = getLogContext("getBySchoolClass");
        
        if (schoolClass == null || schoolClass.trim().isEmpty()) {
            throw new NotFoundExceptionHandle(
                "School class cannot be null or empty", 
                Collections.emptyList(), 
                "StudentModel"
            );
        }
        
        // định dạng lại schoolClass 
        String normalizedClass = schoolClass.trim();
        
        // lấy data từ cache
        String cacheKey = STUDENT_BY_CLASS_CACHE_PREFIX + normalizedClass;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            // Convert từ List<LinkedHashMap> (JSON deserialized) sang List<EntityModel>
            List<EntityModel> cachedStudents = objectMapper.convertValue(
                cached, 
                new TypeReference<List<EntityModel>>() {}
            );
            loggingService.logInfo("Get students by schoolClass from cache: " + cacheKey, logContext);
            return cachedStudents;
        }
        loggingService.logWarn("Not found cache for schoolClass: " + normalizedClass + ", query DB", logContext);
        
        List<StudentEntity> studentEntities = studentRepo.findBySchoolClass(normalizedClass);
        
        // trường hợp class chưa có student nào thì return empty list ( vì vẫn là hợp lệ không phải throw exception)
        if (studentEntities.isEmpty()) {
            loggingService.logInfo("No students found for schoolClass: " + normalizedClass, logContext);
            // Cache empty list với TTL ngắn hơn để tránh cache miss liên tục
            redisTemplate.opsForValue().set(
                cacheKey, 
                Collections.emptyList(), 
                5, 
                TimeUnit.MINUTES
            );
            return Collections.emptyList();
        }
        
        List<Integer> userIds = studentEntities.stream()
                .map(StudentEntity::getUserId)
                .distinct()
                .toList();
        Map<Integer, UserDto> usersById = securityService.getUsersByIds(userIds);

        List<EntityModel> studentModels = new ArrayList<>();
        for (StudentEntity studentEntity : studentEntities) {
            UserDto userDto = usersById.get(studentEntity.getUserId());
            
            if (userDto != null) {
                if (userDto.getProfileData() == null) {
                    userDto.setProfileData(new HashMap<>());
                }
                userDto.getProfileData().put("graduate", studentEntity.getGraduate());
                userDto.getProfileData().put("score", studentEntity.getScore());
                userDto.getProfileData().put("schoolClass", studentEntity.getSchoolClass());
                userDto.getProfileData().put("major", studentEntity.getMajor());
                
                EntityModel studentModel = new EntityModel();
                studentModel.setId(studentEntity.getId());
                studentModel.setUser(userDto);
                
                studentModels.add(studentModel);
                loggingService.logStudentOperation("GET_BY_CLASS", studentEntity.getId(), logContext);
            }
        }
        
        // lưu data vào cache với TTL là 10 phút
        redisTemplate.opsForValue().set(
            cacheKey, 
            studentModels, 
            CACHE_TTL_MINUTES, 
            TimeUnit.MINUTES
        );
        loggingService.logInfo("Save students by schoolClass cache to Redis : " + cacheKey, logContext);
        
        loggingService.logInfo("Get students by schoolClass: " + normalizedClass + " Successfully", logContext);
        return studentModels;
    }

    @Override
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 3)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    @CircuitBreaker(name = "qlsv-service", fallbackMethod = "createFallback")
    public void createByUserId(UserDto user) {
        
        LogContext logContext = getLogContext("createByUserId");

        // Kiểm tra xem đã có student với userId này chưa
        StudentEntity existingStudent = studentRepo.findByUserId(user.getUserId()).orElse(null);
        
        if (existingStudent != null) {
            loggingService.logWarn("Student profile already exists for userId: " + user.getUserId() + 
                ". This may be orphaned from previous failed registration. Deleting and recreating...", logContext);
            
            // Xóa student cũ (orphaned) để tạo lại
            studentRepo.delete(existingStudent);
            studentRepo.flush(); // Flush để đảm bảo DELETE thực thi trước INSERT
            
            loggingService.logInfo("Deleted orphaned student for userId: " + user.getUserId(), logContext);
        }

        // Tạo student mới
        StudentEntity student = new StudentEntity();
        student.setUserId(user.getUserId());
        // Mặc định graduate = false (student mới chưa tốt nghiệp)
        student.setGraduate(false);
        
        // Xử lý profileData an toàn (có thể null hoặc không có các key)
        if (user.getProfileData() != null && !user.getProfileData().isEmpty()) {
            // Collect các field null để log warning cho admin
            List<String> nullFields = new ArrayList<>();
            
            // Trường hợp đặc biệt: nếu có graduate = true trong profileData thì sẽ được override
            Object graduateObj = user.getProfileData().get("graduate");
            if (graduateObj != null) {
                student.setGraduate((Boolean) graduateObj);
            }
            
            // Xử lý schoolClass
            Object classObj = user.getProfileData().get("schoolClass");
            if (classObj != null) {
                student.setSchoolClass(classObj.toString());
            } else {
                nullFields.add("schoolClass");
            }
            
            // Xử lý major
            Object majorObj = user.getProfileData().get("major");
            if (majorObj != null) {
                student.setMajor(majorObj.toString());
            } else {
                nullFields.add("major");
            }
            
            // Log warning nếu có field null (không bao gồm graduate vì đã set default và score vì student mới chưa có điểm)
            if (!nullFields.isEmpty()) {
                loggingService.logWarn("Missing fields in profileData for userId: " + user.getUserId() + 
                    ". Null fields: " + nullFields + ". Admin should review and update profile data.", logContext);
            }
        } else {
            // profileData null/empty từ input → log warning cho admin
            loggingService.logWarn("profileData is null or empty for userId: " + user.getUserId() + 
                ". Student entity created with default graduate=false. Other fields are null. Admin should review and update profile data.", logContext);
        }
        
        studentRepo.save(student);

        // xóa cache khi tạo student mới
        redisTemplate.delete(STUDENT_CACHE_KEY);
        redisTemplate.delete(STUDENT_BY_USER_ID_CACHE_PREFIX + user.getUserId());
        if (student.getSchoolClass() != null) {
            redisTemplate.delete(STUDENT_BY_CLASS_CACHE_PREFIX + student.getSchoolClass());
        }
        loggingService.logInfo("Delete cache after create student userId: " + user.getUserId(), logContext);
        // chỉ log success khi tạo student mới thành công vì phải gửi event qua kafka để update status user
        loggingService.logInfo(
            "Create student profile for userId: " + user.getUserId() + " Successfully", 
            logContext
        );
    }

    @Override
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 3)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    @CircuitBreaker(name = "qlsv-service", fallbackMethod = "updateFallback")
    public EntityModel update(UpdateEntityModel req, UserDto currentUser) {
        LogContext logContext = getLogContext("update");

        if (req.getUser() == null || req.getUser().getUserId() == null) {
            throw new NotFoundExceptionHandle("", Collections.emptyList(), "StudentModel");
        }

        StudentEntity studentEntity = studentRepo.findById(req.getId())
                .orElseThrow(() -> new NotFoundExceptionHandle("", 
                    List.of(String.valueOf(req.getId())), "StudentModel"));

        // Kiểm tra quyền truy cập:
        // - ADMIN và TEACHER có thể update bất kỳ student nào
        // - STUDENT chỉ có thể update thông tin của chính mình
        if (currentUser != null && currentUser.getRole() == Role.STUDENT) {
            // STUDENT chỉ được update chính mình
            if (!studentEntity.getUserId().equals(req.getUser().getUserId())) {
                throw new NotFoundExceptionHandle(
                    "Student can only update their own information", 
                    Collections.emptyList(), 
                    "StudentModel"
                );
            }
        }

        // Update student entity TRƯỚC (trong transaction - có thể rollback nếu có lỗi)
        if (req.getUser().getProfileData() != null && !req.getUser().getProfileData().isEmpty()) {

            List<String> nullFields = new ArrayList<>();

            // Xử lý graduate - chỉ ADMIN/TEACHER mới được update graduate
            // STUDENT không thể tự update graduate của mình
            Object graduateObj = req.getUser().getProfileData().get("graduate");
            Boolean graduate = studentEntity.getGraduate(); // Giữ nguyên graduate hiện tại mặc định
            if (graduateObj != null) {
                // Chỉ cho phép ADMIN hoặc TEACHER update graduate
                if (currentUser != null && (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.TEACHER)) {
                    graduate = (Boolean) graduateObj;
                    loggingService.logInfo("Graduate status updated by " + currentUser.getRole() + " (" + currentUser.getUsername() + 
                        ") for student userId: " + req.getUser().getUserId(), logContext);
                } else {
                    // STUDENT cố gắng update graduate → bỏ qua và log warning
                    loggingService.logWarn("Student (" + (currentUser != null ? currentUser.getUsername() : "unknown") + 
                        ") attempted to update graduate status for userId: " + req.getUser().getUserId() + 
                        ". Graduate update ignored, keeping existing value: " + studentEntity.getGraduate(), logContext);
                }
            }
            
            // Xử lý score - chỉ ADMIN/TEACHER mới được update score
            // STUDENT không thể tự update score của mình
            Object scoreObj = req.getUser().getProfileData().get("score");
            Double score = studentEntity.getScore(); // Giữ nguyên score hiện tại mặc định
            if (scoreObj != null && scoreObj instanceof Number) {
                // Chỉ cho phép ADMIN hoặc TEACHER update score
                if (currentUser != null && (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.TEACHER)) {
                    score = ((Number) scoreObj).doubleValue();
                    loggingService.logInfo("Score updated by " + currentUser.getRole() + " (" + currentUser.getUsername() + 
                        ") for student userId: " + req.getUser().getUserId(), logContext);
                } else {
                    // STUDENT cố gắng update score → bỏ qua và log warning
                    loggingService.logWarn("Student (" + (currentUser != null ? currentUser.getUsername() : "unknown") + 
                        ") attempted to update score for userId: " + req.getUser().getUserId() + 
                        ". Score update ignored, keeping existing value: " + studentEntity.getScore(), logContext);
                }
            }
            
            // Xử lý schoolClass
            Object schoolClassObj = req.getUser().getProfileData().get("schoolClass");
            String schoolClass = null;
            if (schoolClassObj != null) {
                schoolClass = schoolClassObj.toString();
            } else {
                nullFields.add("schoolClass");
            }
            
            Object majorObj = req.getUser().getProfileData().get("major");
            String major = null;
            if (majorObj != null) {
                major = majorObj.toString();
            } else {
                nullFields.add("major");
            }

            if (!nullFields.isEmpty()) {
                loggingService.logWarn("Missing fields in profileData for userId: " + req.getUser().getUserId() + 
                    ". Null fields: " + nullFields + ". Admin should review and update profile data.", logContext);
            }
            
            studentEntity.setGraduate(graduate);
            studentEntity.setScore(score);
            studentEntity.setSchoolClass(schoolClass);
            studentEntity.setMajor(major);
            studentRepo.save(studentEntity);

            loggingService.logStudentOperation("UPDATE", req.getId(), logContext);
            loggingService.logInfo("Update student userId: " + req.getUser().getUserId() + " Successfully", logContext);
        }

        // Gọi security service SAU để update user
        // Nếu security service lỗi → throw exception → Spring @Transactional sẽ rollback toàn bộ transaction
        // → student entity KHÔNG bị update (an toàn)
        // Transaction chỉ commit khi method return thành công (không có exception)
        UserDto user = securityService.updateUser(req.getUser());
        loggingService.logInfo("Update user data for userId: " + user.getUserId(), logContext);

        if (user.getProfileData() == null) {
            user.setProfileData(new HashMap<>());
        }
        user.getProfileData().put("graduate", studentEntity.getGraduate());
        user.getProfileData().put("score", studentEntity.getScore());
        user.getProfileData().put("schoolClass", studentEntity.getSchoolClass());
        user.getProfileData().put("major", studentEntity.getMajor());

        EntityModel studentModel = new EntityModel();
        studentModel.setId(req.getId());
        studentModel.setUser(user);

        // xóa cache khi update student
        redisTemplate.delete(STUDENT_CACHE_KEY);
        redisTemplate.delete(STUDENT_BY_USER_ID_CACHE_PREFIX + req.getUser().getUserId());
        if (studentEntity.getSchoolClass() != null) {
            redisTemplate.delete(STUDENT_BY_CLASS_CACHE_PREFIX + studentEntity.getSchoolClass());
        }
        // Xóa cache paged (dùng pattern matching)
        Set<String> pagedKeys = redisTemplate.keys("students:paged:*");
        if (pagedKeys != null && !pagedKeys.isEmpty()) {
            redisTemplate.delete(pagedKeys);
        }
        loggingService.logInfo("Invalidated all student caches after update userId: " + req.getUser().getUserId(), logContext);

        return studentModel;
    }

    @Override
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 3)
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    @CircuitBreaker(name = "qlsv-service", fallbackMethod = "deletesFallback")
    public Boolean deletes(List<Integer> userIds) {
        LogContext logContext = getLogContext("deletes");

        // Validate và loại bỏ duplicate userIds
        Set<Integer> uniqueUserIds = new LinkedHashSet<>();
        List<Integer> duplicates = new ArrayList<>();
        for (Integer userId : userIds) {
            if (!uniqueUserIds.add(userId)) {
                duplicates.add(userId);
            }
        }
        
        if (!duplicates.isEmpty()) {
            loggingService.logWarn("Duplicate userIds detected in delete request: " + duplicates 
                + ". They will be processed only once.", logContext);
        }
        
        List<Integer> uniqueUserIdsList = new ArrayList<>(uniqueUserIds);

        List<String> listIDNotFound = new ArrayList<>();
        List<StudentEntity> studentEntitiesToDelete = new ArrayList<>();
        
        for(Integer userId : uniqueUserIdsList) {
            StudentEntity studentEntity = studentRepo.findByUserId(userId)
                    .orElse(null);
            if (studentEntity != null) {
                studentEntitiesToDelete.add(studentEntity);
            } else {
                listIDNotFound.add(String.valueOf(userId));
            }
        }
        
        if (!listIDNotFound.isEmpty()) {
            loggingService.logError("Found IDs not exist: " + listIDNotFound, null, logContext);
            throw new NotFoundExceptionHandle("", listIDNotFound, "StudentModel");
        }

        // Xóa student entities TRƯỚC (trong transaction - có thể rollback nếu có lỗi)
        // Nếu student xóa thành công, transaction sẽ commit
        studentRepo.deleteAll(studentEntitiesToDelete);
        loggingService.logInfo("Deleted students with userIds: " + uniqueUserIdsList, logContext);

        // Gọi security service SAU để xóa users
        // Nếu security service lỗi → throw exception → sẽ rollback toàn bộ → student không mât (an toàn)
        // Transaction chỉ commit khi method return thành công (không có exception)
        List<Integer> deletedUserIds = securityService.deleteUsers(uniqueUserIdsList);
        loggingService.logInfo("Deleted users with ids: " + deletedUserIds, logContext);
        
        // Invalidate caches
        redisTemplate.delete(STUDENT_CACHE_KEY);
        // Invalidate cache của từng student bị xóa
        Set<String> schoolClassesToInvalidate = new HashSet<>();
        for (StudentEntity studentEntity : studentEntitiesToDelete) {
            redisTemplate.delete(STUDENT_BY_USER_ID_CACHE_PREFIX + studentEntity.getUserId());
            if (studentEntity.getSchoolClass() != null) {
                schoolClassesToInvalidate.add(studentEntity.getSchoolClass());
            }
        }
        // Invalidate cache của các lớp có student bị xóa
        for (String schoolClass : schoolClassesToInvalidate) {
            redisTemplate.delete(STUDENT_BY_CLASS_CACHE_PREFIX + schoolClass);
        }
        loggingService.logInfo("Invalidated caches after delete students (userIds: " + uniqueUserIdsList + 
            ", schoolClasses: " + schoolClassesToInvalidate + ")", logContext);
        return true;
    }

    // Paged method
    @Override
    @CircuitBreaker(name = "qlsv-service", fallbackMethod = "getsPagedFallback")
    public PagedResponseModel<EntityModel> getsPaged(PagedRequestModel pagedRequest) {
        LogContext logContext = getLogContext("getsPaged");
        
        // Tạo cache key cho phân trang
        String cacheKey = String.format("students:paged:page=%d:size=%d:sort=%s:%s", 
            pagedRequest.getPage(), 
            pagedRequest.getSize(), 
            pagedRequest.getSortBy(), 
            pagedRequest.getSortDirection());
        
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            loggingService.logInfo("Get paged data from Redis cache : " + cacheKey, logContext);
            PagedResponseModel<EntityModel> cachedPaged = objectMapper.convertValue(cached, 
                    objectMapper.getTypeFactory().constructParametricType(
                        PagedResponseModel.class, EntityModel.class));
            return cachedPaged;
        }
        
        loggingService.logWarn("Not found paged cache, Query DB", logContext);
        
        // Tạo Sort object
        Sort sort = Sort.by(
            "asc".equalsIgnoreCase(pagedRequest.getSortDirection()) ? Sort.Direction.ASC : Sort.Direction.DESC
            ,pagedRequest.getSortBy()
        );
        
        // Tạo Pageable object, pageable là đối tượng để phân trang
        Pageable pageable = PageRequest.of(
            pagedRequest.getPage(), 
            pagedRequest.getSize(), 
            sort
        );
        
        // lấy data dựa trên pageable đã cấu hình ( số trang, số lượng phần tử trên mỗi trang, cách sắp xếp )
        Page<StudentEntity> studentPage = studentRepo.findAll(pageable);
        
        if (studentPage.isEmpty()) {
            loggingService.logWarn("No students found in database", logContext);
            throw new NotFoundExceptionHandle(
                "", 
                List.of(String.valueOf(pageable.getPageNumber())), 
                "StudentModel");
        }
        
        // tạo list userIds để gửi qua security service
        List<Integer> userIds = studentPage.getContent().stream()
                .map(StudentEntity::getUserId)
                .distinct()
                .toList();
        Map<Integer, UserDto> usersById = securityService.getUsersByIds(userIds);
 
        List<EntityModel> studentModels = new ArrayList<>();
        for (StudentEntity studentEntity : studentPage.getContent()) {
            UserDto userDto = usersById.get(studentEntity.getUserId());
            
            if (userDto != null) {
                if (userDto.getProfileData() == null) {
                    userDto.setProfileData(new HashMap<>());
                }
                userDto.getProfileData().put("graduate", studentEntity.getGraduate());
                userDto.getProfileData().put("score", studentEntity.getScore());
                userDto.getProfileData().put("schoolClass", studentEntity.getSchoolClass());
                userDto.getProfileData().put("major", studentEntity.getMajor());
                
                EntityModel studentModel = new EntityModel();
                studentModel.setId(studentEntity.getId());
                studentModel.setUser(userDto);
                
                studentModels.add(studentModel);
                loggingService.logStudentOperation("GET_PAGED", studentEntity.getId(), logContext);
            }
        }
        
        PagedResponseModel<EntityModel> pagedResponse = new PagedResponseModel<>(
            studentModels,
            pageable.getPageNumber(),
            pageable.getPageSize(),
            studentPage.getTotalElements()
        );
        
        // Cache kết quả phân trang (cache trong 5 phút)
        redisTemplate.opsForValue().set(cacheKey, pagedResponse, 5, TimeUnit.MINUTES);
        loggingService.logInfo("Save paged cache to Redis : " + cacheKey, logContext);
        
        loggingService.logInfo("Gets Paged Students Successfully", logContext);
        return pagedResponse;
    }


    // filter method

    @Override
    @CircuitBreaker(name = "qlsv-service", fallbackMethod = "filterFallback")
    public List<EntityModel> filter(Integer id, String firstName, String lastName, 
                                    Integer age, Gender gender, String email, 
                                    String phoneNumber, Double score, String schoolClass, 
                                    String major, Boolean graduate
    ) {
        LogContext logContext = getLogContext("filter");

        String cacheKey = String.format(
    "students:filter:id=%d:firstName=%s:lastName=%s:age=%d:gender=%s:email=%s:phoneNumber=%s:score=%f:schoolClass=%s:major=%s:graduate=%b", 
            id, firstName, lastName, age, gender, email, phoneNumber, score, schoolClass, major, graduate
        );
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            loggingService.logInfo("Get filtered data from Redis cache : " + cacheKey, logContext);
            List<EntityModel> cachedStudents = objectMapper.convertValue(
                cached, 
                new TypeReference<List<EntityModel>>() {}
            );
            return cachedStudents;
        }

        // Đếm số điều kiện được truyền vào
        int conditionCount = 0;
        boolean hasIdCondition = (id != null && id > 0);
        boolean hasFirstNameCondition = (firstName != null && !firstName.trim().isEmpty());
        boolean hasLastNameCondition = (lastName != null && !lastName.trim().isEmpty());
        boolean hasAgeCondition = (age != null && age > 0);
        boolean hasGenderCondition = gender != null;
        boolean hasEmailCondition = email != null && !email.trim().isEmpty();
        boolean hasPhoneNumberCondition = phoneNumber != null && !phoneNumber.trim().isEmpty();
        boolean hasScoreCondition = score != null && score > 0;
        boolean hasSchoolClassCondition = schoolClass != null && !schoolClass.trim().isEmpty();
        boolean hasMajorCondition = major != null && !major.trim().isEmpty();
        boolean hasGraduateCondition = graduate != null;
        
        if (hasIdCondition) conditionCount++;
        if (hasFirstNameCondition) conditionCount++;
        if (hasLastNameCondition) conditionCount++;
        if (hasAgeCondition) conditionCount++;
        if (hasGenderCondition) conditionCount++;
        if (hasEmailCondition) conditionCount++;
        if (hasPhoneNumberCondition) conditionCount++;
        if (hasScoreCondition) conditionCount++;
        if (hasSchoolClassCondition) conditionCount++;
        if (hasMajorCondition) conditionCount++;
        if (hasGraduateCondition) conditionCount++;

        if (conditionCount == 0) {
            loggingService.logWarn("No conditions provided for filtering", logContext);
            return gets();
        }
        loggingService.logInfo("Filtering with " + conditionCount + " conditions", logContext);

        List<EntityModel> studentModels = new ArrayList<>();
        List<StudentEntity> studentEntities = studentRepo.findAll();

        if (studentEntities.isEmpty()) {
            loggingService.logWarn("No students found in database", logContext);
            throw new NotFoundExceptionHandle("", Collections.emptyList(), "StudentModel");
        }

        Map<Integer, UserDto> usersById = securityService.getUsersByIds(studentEntities.stream()
                .map(StudentEntity::getUserId)
                .distinct()
                .toList()
            );

        for (StudentEntity studentEntity : studentEntities) {
            int matchedConditions = 0;

            UserDto userDto = usersById.get(studentEntity.getUserId());
            
            if (userDto != null) {
                // Kiểm tra từng điều kiện và đếm số điều kiện thỏa mãn
                if (hasIdCondition && studentEntity.getId() == id) {
                    matchedConditions++;
                }
                if (hasFirstNameCondition && userDto.getFirstName() != null &&
                    userDto.getFirstName().toLowerCase().contains(firstName.toLowerCase())
                ) {
                    matchedConditions++;
                }
                if (hasLastNameCondition && userDto.getLastName() != null &&
                    userDto.getLastName().toLowerCase().contains(lastName.toLowerCase())
                ) {
                    matchedConditions++;
                }
                if (hasAgeCondition && userDto.getAge() == age) {
                    matchedConditions++;
                }
                if (hasGenderCondition && userDto.getGender() == gender) {
                    matchedConditions++;
                }
                if (hasEmailCondition && userDto.getEmail() == email) {
                    matchedConditions++;
                }
                if (hasPhoneNumberCondition && userDto.getPhoneNumber() == phoneNumber) {
                    matchedConditions++;
                }
                if (hasScoreCondition && studentEntity.getScore() == score) {
                    matchedConditions++;
                }
                if (hasSchoolClassCondition && studentEntity.getSchoolClass() != null &&
                    studentEntity.getSchoolClass().toLowerCase().contains(schoolClass.toLowerCase())
                ) {
                    matchedConditions++;
                }
                if (hasMajorCondition && studentEntity.getMajor() == major) {
                    matchedConditions++;
                }
                if (hasGraduateCondition && studentEntity.getGraduate() == graduate) {
                    matchedConditions++;
                }
                // Chỉ thêm student nếu thỏa mãn TẤT CẢ các điều kiện được truyền vào
                if (matchedConditions == conditionCount) {
                    if (userDto.getProfileData() == null) {
                        userDto.setProfileData(new HashMap<>());
                    }
                    userDto.getProfileData().put("graduate", studentEntity.getGraduate());
                    userDto.getProfileData().put("score", studentEntity.getScore());
                    userDto.getProfileData().put("schoolClass", studentEntity.getSchoolClass());
                    userDto.getProfileData().put("major", studentEntity.getMajor());
                    
                    // Tạo EntityModel với user info đầy đủ
                    EntityModel studentModel = new EntityModel();
                    studentModel.setId(studentEntity.getId());
                    studentModel.setUser(userDto);
                    
                    studentModels.add(studentModel);
                }
            }
        }
        // Cache kết quả filter (cache trong 5 phút)
        redisTemplate.opsForValue().set(cacheKey, studentModels, 5, TimeUnit.MINUTES);
        loggingService.logInfo("Save filtered cache to Redis : " + cacheKey, logContext);
        
        loggingService.logInfo("Filter Students Successfully. Found " + studentModels.size() + " students with the given filters", logContext);
        return studentModels;
    }

    // fallback method
    public List<EntityModel> getsFallback(Exception e) {
        LogContext logContext = getLogContext("getsFallback");
        loggingService.logError("Exception in gets method , call fallback method ", e, logContext);

        // lấy data từ cache nếu có
        String cacheKey = STUDENT_CACHE_KEY;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            // Convert từ List<LinkedHashMap> (JSON deserialized) sang List<EntityModel>
            List<EntityModel> cachedStudents = objectMapper.convertValue(
                cached, 
                new TypeReference<List<EntityModel>>() {}
            );
            loggingService.logInfo("Fallback: Get students from cache: " + cacheKey, logContext);
            return cachedStudents;
        }

        loggingService.logError("No cached data found, throwing ServiceUnavailableException", e, logContext);
        throw new ServiceUnavailableExceptionHandle("QLSV service is currently unavailable", "Please try again later");
    }

    public EntityModel getByUserIdFallback(Integer userId, Exception e) {
        LogContext logContext = getLogContext("getByUserIdFallback");
        loggingService.logError("Exception in getByUserId method, calling fallback method", e, logContext);

        // lấy data từ cache nếu có
        if (userId != null) {
            String cacheKey = STUDENT_BY_USER_ID_CACHE_PREFIX + userId;
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                // Convert từ LinkedHashMap (JSON deserialized) sang EntityModel
                EntityModel cachedStudent = objectMapper.convertValue(
                    cached, 
                    EntityModel.class
                );
                loggingService.logInfo("Fallback: Get student by userId from cache: " + cacheKey, logContext);
                return cachedStudent;
            }
        }
        
        loggingService.logError("No cached data found, throwing ServiceUnavailableException", e, logContext);
        loggingService.logError("Failed to get student profile, service is currently unavailable. Cache also not available.", e, logContext);
        throw new ServiceUnavailableExceptionHandle("QLSV service is currently unavailable", "Please try again later");
    }

    public List<EntityModel> getBySchoolClassFallback(String schoolClass, Exception e) {
        LogContext logContext = getLogContext("getBySchoolClassFallback");
        loggingService.logError("Exception in getBySchoolClass method, calling fallback method", e, logContext);

        // lấy data từ cache nếu có
        if (schoolClass != null && !schoolClass.trim().isEmpty()) {
            String cacheKey = STUDENT_BY_CLASS_CACHE_PREFIX + schoolClass.trim();
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                // Convert từ List<LinkedHashMap> (JSON deserialized) sang List<EntityModel>
                List<EntityModel> cachedStudents = objectMapper.convertValue(
                    cached, 
                    new TypeReference<List<EntityModel>>() {}
                );
                loggingService.logInfo("Fallback: Get students by schoolClass from cache: " + cacheKey, logContext);
                return cachedStudents;
            }
        }
        
        loggingService.logError("No cached data found, throwing ServiceUnavailableException", e, logContext);
        loggingService.logError("Failed to get students by school class, service is currently unavailable. Cache also not available.", e, logContext);
        throw new ServiceUnavailableExceptionHandle("QLSV service is currently unavailable", "Please try again later");
    }

    public void createFallback(Exception e) {
        LogContext logContext = getLogContext("createFallback");
        loggingService.logError("Exception in create method, calling fallback method", e, logContext);
        loggingService.logError("Failed to create student profile, service is currently unavailable", e, logContext);
        throw new ServiceUnavailableExceptionHandle("QLSV service is currently unavailable", "Please try again later");
    }

    public EntityModel updateFallback(Exception e) {
        LogContext logContext = getLogContext("updateFallback");
        loggingService.logError("Exception in update method, calling fallback method", e, logContext);
        loggingService.logError("Failed to update student profile, service is currently unavailable", e, logContext);
        throw new ServiceUnavailableExceptionHandle("QLSV service is currently unavailable", "Please try again later");
    }

    public Boolean deletesFallback(Exception e) {
        LogContext logContext = getLogContext("deletesFallback");
        loggingService.logError("Exception in deletes method, calling fallback method", e, logContext);
        loggingService.logError("Failed to delete students, service is currently unavailable", e, logContext);
        throw new ServiceUnavailableExceptionHandle("QLSV service is currently unavailable", "Please try again later");
    }

    public PagedResponseModel<EntityModel> getsPagedFallback(PagedRequestModel pagedRequest, Exception e) {
        LogContext logContext = getLogContext("getsPagedFallback");
        loggingService.logError("Exception in getsPaged method, calling fallback method", e, logContext);

        // lấy data từ cache nếu có (dùng cùng cache key như getsPaged)
        if (pagedRequest != null) {
            String cacheKey = String.format("students:paged:page=%d:size=%d:sort=%s:%s", 
                pagedRequest.getPage(), 
                pagedRequest.getSize(), 
                pagedRequest.getSortBy(), 
                pagedRequest.getSortDirection());
            
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                // Convert từ LinkedHashMap (JSON deserialized) sang PagedResponseModel<EntityModel>
                PagedResponseModel<EntityModel> cachedPaged = objectMapper.convertValue(
                    cached, 
                    new TypeReference<PagedResponseModel<EntityModel>>() {}
                    );
                loggingService.logInfo("Fallback: Get paged students from cache: " + cacheKey, logContext);
                return cachedPaged;
            }
        }

        loggingService.logError("No cached data found, throwing ServiceUnavailableException", e, logContext);
        loggingService.logError("Failed to get paged students, service is currently unavailable. Cache also not available.", e, logContext);
        throw new ServiceUnavailableExceptionHandle("QLSV service is currently unavailable", "Please try again later");
    }

    public List<EntityModel> filterFallback(Integer id, String firstName, String lastName, 
                                            Integer age, Gender gender, String email, 
                                            String phoneNumber, Double score, String schoolClass, 
                                            String major, Boolean graduate, Exception e) {
        LogContext logContext = getLogContext("filterFallback");
        loggingService.logError("Exception in filter method, calling fallback method", e, logContext);

        String cacheKey = String.format(
    "students:filter:id=%d:firstName=%s:lastName=%s:age=%d:gender=%s:email=%s:phoneNumber=%s:score=%f:schoolClass=%s:major=%s:graduate=%b", 
            id, firstName, lastName, age, gender, email, phoneNumber, score, schoolClass, major, graduate
        );
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            List<EntityModel> cachedStudents = objectMapper.convertValue(
                cached, 
                new TypeReference<List<EntityModel>>() {}
            );
            loggingService.logInfo("Fallback: Get filtered data from cache: " + cacheKey, logContext);
            return cachedStudents;
        }
        loggingService.logError("No cached data found, throwing ServiceUnavailableException", e, logContext);
        loggingService.logError("Failed to filter students, service is currently unavailable. Cache also not available.", e, logContext);
        throw new ServiceUnavailableExceptionHandle("QLSV service is currently unavailable", "Please try again later");
    }
}
