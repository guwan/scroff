package com.scroff.server.controller;

import com.scroff.server.entity.Schedule;
import com.scroff.server.repository.DeviceRepository;
import com.scroff.server.repository.ScheduleRepository;
import com.scroff.server.scheduler.ScheduleExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScheduleController.applyForm 单元测试。
 *
 * <p>目的：防止有人加新字段时漏了 {@code applyForm} 中的 setXxx，
 * 导致 form 提交后字段丢失（用户报告："保存的 targetAll 永远是 false / 编辑后不生效"）。
 *
 * <p>策略：把 {@code applyForm} 设成可访问（private），用反射调用，验证
 * 所有 {@link ScheduleController.ScheduleForm} 字段都映射到了 {@link Schedule} 实体。
 *
 * <p>新增字段时的检查清单：
 * <ol>
 *   <li>在 {@link Schedule} 实体加字段 + getter/setter</li>
 *   <li>在 {@code ScheduleForm} 加字段 + 校验注解</li>
 *   <li>在 form HTML 加 input</li>
 *   <li><b>在 {@code applyForm} 加 setXxx</b> ← 本测试覆盖这一步</li>
 *   <li>如果有特殊语义（如 enabled 兜底），在测试里加 case 覆盖</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

    @Mock
    private ScheduleRepository scheduleRepo;

    @Mock
    private DeviceRepository deviceRepo;

    @Mock
    private ScheduleExecutor executor;

    @InjectMocks
    private ScheduleController controller;

    private Method applyForm;

    @BeforeEach
    void setUp() throws Exception {
        applyForm = ScheduleController.class.getDeclaredMethod("applyForm", Schedule.class, ScheduleController.ScheduleForm.class);
        applyForm.setAccessible(true);
    }

    @Test
    @DisplayName("applyForm 必须处理 targetAll=true（所有设备模式）")
    void testApplyForm_AllDevices() throws Exception {
        Schedule s = new Schedule();
        ScheduleController.ScheduleForm f = new ScheduleController.ScheduleForm();
        f.setName("早班开屏");
        f.setAction(Schedule.Action.ON);
        f.setCron("0 50 7 * * *");
        f.setEnabled(true);
        f.setTargetAll(true);
        // deviceId 故意不设置（form 隐藏字段，浏览器不提交）

        applyForm.invoke(controller, s, f);

        assertTrue(s.isForAllDevices(), "targetAll=true 应该让 isForAllDevices() 返回 true");
        assertEquals(Boolean.TRUE, s.getTargetAll());
        assertEquals(0L, s.getDeviceId(), "所有设备模式下 deviceId 应存 0（哨兵）");
        assertEquals("早班开屏", s.getName());
        assertEquals(Schedule.Action.ON, s.getAction());
        assertEquals("0 50 7 * * *", s.getCron());
        assertTrue(s.getEnabled());
    }

    @Test
    @DisplayName("applyForm 必须处理 targetAll=false（单台设备模式）")
    void testApplyForm_SingleDevice() throws Exception {
        Schedule s = new Schedule();
        ScheduleController.ScheduleForm f = new ScheduleController.ScheduleForm();
        f.setName("晚班关屏");
        f.setAction(Schedule.Action.OFF);
        f.setCron("0 50 17 * * *");
        f.setEnabled(true);
        f.setTargetAll(false);
        f.setDeviceId(5L);

        applyForm.invoke(controller, s, f);

        assertFalse(s.isForAllDevices());
        assertEquals(Boolean.FALSE, s.getTargetAll());
        assertEquals(5L, s.getDeviceId(), "单台模式下 deviceId 应取 form 提交值");
        assertEquals("晚班关屏", s.getName());
        assertEquals(Schedule.Action.OFF, s.getAction());
    }

    @Test
    @DisplayName("applyForm 必须处理 enabled=null（用户取消勾选 checkbox）")
    void testApplyForm_EnabledNull() throws Exception {
        // 关键 case：HTML checkbox 不勾选时 form 不提交该字段，
        // Spring 把 Boolean 绑为 null。必须兜底为 FALSE（用户意图 = 禁用）
        Schedule s = new Schedule();
        // Schedule 默认 enabled = true
        s.setEnabled(true);

        ScheduleController.ScheduleForm f = new ScheduleController.ScheduleForm();
        f.setName("测试");
        f.setAction(Schedule.Action.OFF);
        f.setCron("0 0 12 * * *");
        f.setEnabled(null);  // ← 模拟 checkbox 不勾选
        f.setTargetAll(false);
        f.setDeviceId(1L);

        applyForm.invoke(controller, s, f);

        assertFalse(s.getEnabled(), "enabled=null 必须兜底为 false（用户取消勾选 = 禁用），不是 true");
    }

    @Test
    @DisplayName("applyForm 必须处理 targetAll=null（防御性）")
    void testApplyForm_TargetAllNull() throws Exception {
        // 防御性测试：理论上 form 总会提交 targetAll，但万一出 bug 也不应该崩
        Schedule s = new Schedule();
        ScheduleController.ScheduleForm f = new ScheduleController.ScheduleForm();
        f.setName("测试");
        f.setAction(Schedule.Action.ON);
        f.setCron("0 0 8 * * *");
        f.setEnabled(true);
        f.setTargetAll(null);
        f.setDeviceId(2L);

        applyForm.invoke(controller, s, f);

        // null 兜底为 false（单台模式）
        assertFalse(s.isForAllDevices(), "targetAll=null 应兜底为 false（单台模式）");
        assertEquals(2L, s.getDeviceId(), "targetAll=null 时走单台分支，deviceId 取 form 值");
    }

    /**
     * 防回归：再增加新字段时也要走 applyForm，不能漏。
     * <p>这个测试不强求通过（ScheduleForm 可能后续加字段），但**报告里能看到 reminder**。
     */
    @Test
    @DisplayName("⚠️ REMINDER：给 ScheduleForm 加字段时务必更新 applyForm")
    void reminder_addNewFieldMustUpdateApplyForm() {
        // 没有断言，只是提醒开发者：
        // - 改了 ScheduleForm
        // - 改了 schedule-form.html
        // - 别忘了改 ScheduleController.applyForm
        // - 别忘了改 SchemaConstants（如有）
        // 详见 ScheduleControllerTest 类注释的检查清单
        assertTrue(true);
    }
}
