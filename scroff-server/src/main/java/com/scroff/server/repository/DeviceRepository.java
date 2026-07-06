package com.scroff.server.repository;

import com.scroff.server.entity.Device;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    /** 状态统计（用于 dashboard） */
    long countByStatus(Device.Status status);

    /** 所有启用的设备：启动时自动连接 */
    List<Device> findAllByEnabledTrue();

    Optional<Device> findByHostAndAdbPort(String host, Integer adbPort);

    Page<Device> findAll(Pageable pageable);

    /**
     * 快速更新心跳字段，避免加载整个实体。
     * clearAutomatically=true 防止 JPA 一级缓存里残留旧值，让紧跟其后的 findById 拿到新数据。
     */
    @Modifying(clearAutomatically = true)
    @Query("update Device d set d.status = :status, d.lastSeenAt = :now, d.lastError = :err where d.id = :id")
    int updateStatus(@Param("id") Long id,
                     @Param("status") Device.Status status,
                     @Param("now") LocalDateTime now,
                     @Param("err") String err);
}
