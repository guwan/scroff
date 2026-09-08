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
     * 查询同 cron 的"指定设备 mode"schedule 对应的所有 device id。
     * 用于"所有设备 mode"触发时排除被单台/multi schedule 接管的设备，实现"指定设备 > 所有设备"优先级。
     *
     * <p>实现：schedule_device 是独立关联表，JPQL JOIN s.deviceIds 直接拉平。
     * 老数据兜底：对那些 device_ids 列还没迁移到 schedule_device、只有 device_id > 0 的 schedule，
     * 再用原生 SQL UNION 补一轮。
     */
    @Query("""
            SELECT DISTINCT d FROM Schedule s JOIN s.deviceIds d
            WHERE s.enabled = true
              AND s.targetAll = false
              AND s.cron = :cron
            """)
    List<Long> findOverridingDeviceIds(@Param("cron") String cron);

    /**
     * 兜底查询：老 schedule 还没迁移到 schedule_device，只有 device_id > 0 的情况。
     * 与上面的 JPQL 结果 UNION 使用。
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT s.device_id FROM schedule s
            WHERE s.enabled = 1
              AND s.target_all = 0
              AND s.cron = :cron
              AND s.device_id > 0
              AND NOT EXISTS (SELECT 1 FROM schedule_device sd WHERE sd.schedule_id = s.id)
            """)
    List<Long> findOverridingDeviceIdsLegacy(@Param("cron") String cron);
}
