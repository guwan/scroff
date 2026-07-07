package com.scroff.server.repository;

import com.scroff.server.entity.Schedule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    List<Schedule> findAllByEnabledTrue();

    Page<Schedule> findAll(Pageable pageable);

    Page<Schedule> findByDeviceId(Long deviceId, Pageable pageable);

    long countByEnabledTrue();

    /** 触发后回写执行结果，避免 select 再 update 两次往返 */
    @Modifying
    @Query("update Schedule s set s.lastRunAt = :now, s.lastRunStatus = :status, s.lastRunMessage = :msg where s.id = :id")
    int updateLastRun(@Param("id") Long id,
                      @Param("now") LocalDateTime now,
                      @Param("status") Schedule.LastRunStatus status,
                      @Param("msg") String msg);

    /**
     * 查询同 cron 的"单台 mode"schedule 对应的 device_id 列表。
     * 用于"所有设备 mode"触发时排除被单台 schedule 接管的设备，实现"单台 > 所有"优先级。
     *
     * <p>例：所有设备 schedule cron = "0 50 17 * * *"
     *     设备 5 有单台 schedule cron = "0 50 17 * * *" （同 cron）→ 设备 5 被"接管"
     *     设备 6 有单台 schedule cron = "0 55 17 * * *" （不同 cron）→ 设备 6 不被接管
     *
     * <p>只匹配 enabled=true + target_all=false + cron 相等的活跃 schedule。
     * DISTINCT 处理"同一设备多个同 cron 单台 schedule"的去重。
     */
    @Query("SELECT DISTINCT s.deviceId FROM Schedule s " +
           "WHERE s.enabled = true " +
           "AND s.targetAll = false " +
           "AND s.cron = :cron " +
           "AND s.deviceId IS NOT NULL")
    List<Long> findOverridingDeviceIds(@Param("cron") String cron);
}
