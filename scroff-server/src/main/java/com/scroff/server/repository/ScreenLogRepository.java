package com.scroff.server.repository;

import com.scroff.server.entity.ScreenLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScreenLogRepository extends JpaRepository<ScreenLog, Long> {

    Page<ScreenLog> findAllByOrderByExecutedAtDesc(Pageable pageable);

    Page<ScreenLog> findByDeviceIdOrderByExecutedAtDesc(Long deviceId, Pageable pageable);
}
