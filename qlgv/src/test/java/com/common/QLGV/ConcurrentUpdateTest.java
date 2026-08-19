package com.common.QLGV;

import com.common.QLGV.entities.TeacherEntity;
import com.common.QLGV.repositories.TeacherRepo;
import com.common.QLGV.services.imp.TeacherServiceImp;
import com.model_shared.models.user.UpdateEntityModel;
import com.model_shared.models.user.UpdateUserDto;
import com.model_shared.enums.Gender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
class ConcurrentUpdateTest {

    @Autowired
    private TeacherServiceImp teacherServiceImp;
    
    @Autowired
    private TeacherRepo teacherRepo;

    @Test
    @Transactional
    @Rollback(false) // Để xem kết quả trong DB
    void testConcurrentUpdate() throws InterruptedException {
        // Tìm một teacher để test (thay đổi userId phù hợp với DB của bạn)
        Integer testUserId = 5; // Thay đổi theo userId có trong DB
        TeacherEntity teacher = teacherRepo.findByUserId(testUserId)
            .orElseThrow(() -> new RuntimeException("Teacher not found with userId: " + testUserId));
        
        System.out.println("=== Bắt đầu test concurrent update ===");
        System.out.println("Teacher ID: " + teacher.getId());
        System.out.println("Initial Version: " + teacher.getVersion());
        System.out.println("Initial Department: " + teacher.getDepartment());
        
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger optimisticLockCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        
        // Tạo các thread để update đồng thời
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    // Đợi tất cả thread sẵn sàng
                    startLatch.await();
                    
                    // Đọc lại entity để có version mới nhất
                    TeacherEntity currentEntity = teacherRepo.findById(teacher.getId())
                        .orElseThrow(() -> new RuntimeException("Teacher not found"));
                    
                    Long versionAtStart = currentEntity.getVersion();
                    String originalDept = currentEntity.getDepartment() != null 
                        ? currentEntity.getDepartment().replaceAll("_Thread\\d+", "") 
                        : "";
                    
                    // Tạo UpdateEntityModel
                    UpdateEntityModel updateReq = new UpdateEntityModel();
                    updateReq.setId(teacher.getId());
                    
                    UpdateUserDto updateUserDto = UpdateUserDto.builder()
                        .userId(testUserId)
                        .firstName("Test")
                        .lastName("User")
                        .gender(Gender.NAM)
                        .birth(LocalDate.of(1990, 1, 1))
                        .phoneNumber("0123456789")
                        .email("test@example.com")
                        .profileData(new HashMap<>())
                        .build();
                    
                    updateUserDto.getProfileData().put("department", originalDept + "_Thread" + threadIndex);
                    updateUserDto.getProfileData().put("classManaging", currentEntity.getClassManaging() != null 
                        ? currentEntity.getClassManaging() : "");
                    
                    updateReq.setUser(updateUserDto);
                    
                    // Gọi update
                    teacherServiceImp.update(updateReq);
                    
                    // Lấy version sau khi update
                    TeacherEntity updatedEntity = teacherRepo.findById(teacher.getId())
                        .orElseThrow(() -> new RuntimeException("Teacher not found"));
                    
                    results.add(String.format("Thread %d: SUCCESS - Version %d -> %d, Dept: %s", 
                        threadIndex, versionAtStart, updatedEntity.getVersion(), 
                        updatedEntity.getDepartment()));
                    successCount.incrementAndGet();
                    
                } catch (OptimisticLockingFailureException e) {
                    results.add(String.format("Thread %d: OPTIMISTIC_LOCK_FAILURE - %s", 
                        threadIndex, e.getMessage()));
                    optimisticLockCount.incrementAndGet();
                } catch (Exception e) {
                    results.add(String.format("Thread %d: ERROR - %s: %s", 
                        threadIndex, e.getClass().getSimpleName(), e.getMessage()));
                    errorCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Bắt đầu tất cả thread cùng lúc
        Thread.sleep(100); // Đợi tất cả thread sẵn sàng
        startLatch.countDown();
        
        // Đợi tất cả thread hoàn thành (tối đa 30 giây)
        boolean finished = finishLatch.await(30, TimeUnit.SECONDS);
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Lấy kết quả cuối cùng từ DB
        TeacherEntity finalEntity = teacherRepo.findById(teacher.getId())
            .orElseThrow(() -> new RuntimeException("Teacher not found"));
        
        System.out.println("\n=== Kết quả test ===");
        System.out.println("Thread Count: " + threadCount);
        System.out.println("Success: " + successCount.get());
        System.out.println("Optimistic Lock Failures: " + optimisticLockCount.get());
        System.out.println("Errors: " + errorCount.get());
        System.out.println("\nFinal Version: " + finalEntity.getVersion());
        System.out.println("Final Department: " + finalEntity.getDepartment());
        System.out.println("\nChi tiết từng thread:");
        results.forEach(System.out::println);
        
        // Kiểm tra kết quả
        if (optimisticLockCount.get() > 0) {
            System.out.println("\n✅ Optimistic locking hoạt động đúng! Có " + 
                optimisticLockCount.get() + " thread bị optimistic lock failure.");
        } else {
            System.out.println("\n⚠️ Không có optimistic lock failure. Có thể các transaction commit tuần tự.");
        }
        
        if (!finished) {
            System.out.println("\n⚠️ Test timeout - một số thread chưa hoàn thành");
        }
    }
}

