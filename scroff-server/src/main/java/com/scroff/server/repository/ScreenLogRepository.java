package com.scroff.server.repository;

import com.scroff.server.entity.ScreenLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ScreenLogRepository extends JpaRepository<ScreenLog, Long> {

    Page<ScreenLog> findAllByOrderByExecutedAtDesc(Pageable pageable);

    Page<ScreenLog> findByDeviceIdOrderByExecutedAtDesc(Long deviceId, Pageable pageable);

    /**
     * 日志搜索：各条件为 null 表示不过滤。
     * - deviceName 匹配日志冗余的 device_name（设备删除后仍能搜到历史日志）
     * - ip 模糊匹配 device_address（host:port）
     * - category/location 来自设备表（按设备当前分类/位置过滤），
     *   LEFT JOIN 保证设备已删除的日志仍能按其他条件查出来。
     * - startTime/endTime 为 executedAt 的闭区间。
     */
    @Query("""
            SELECT l FROM ScreenLog l
            LEFT JOIN Device d ON d.id = l.deviceId
            WHERE (:deviceId IS NULL OR l.deviceId = :deviceId)
              AND (:deviceName IS NULL OR l.deviceName LIKE %:deviceName%)
              AND (:action IS NULL OR l.action = :action)
              AND (:ip IS NULL OR l.deviceAddress LIKE %:ip%)
              AND (:category IS NULL OR d.category LIKE %:category%)
              AND (:location IS NULL OR d.location LIKE %:location%)
              AND (:startTime IS NULL OR l.executedAt >= :startTime)
              AND (:endTime IS NULL OR l.executedAt <= :endTime)
            ORDER BY l.executedAt DESC, l.id DESC
            """)
    Page<ScreenLog> search(@Param("deviceId") Long deviceId,
                           @Param("deviceName") String deviceName,
                           @Param("action") ScreenLog.Action action,
                           @Param("ip") String ip,
                           @Param("category") String category,
                           @Param("location") String location,
                           @Param("startTime") LocalDateTime startTime,
                           @Param("endTime") LocalDateTime endTime,
                           Pageable pageable);
}
