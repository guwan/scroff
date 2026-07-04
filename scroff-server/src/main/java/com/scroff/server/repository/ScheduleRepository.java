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
}
