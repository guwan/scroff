/**
 * 全局类型定义
 * 严格模式：禁止 any，所有接口必须显式声明
 */

/** 时分结构（24 小时制） */
export interface HourMinute {
  hour: number; // 0 - 23
  minute: number; // 0 - 59
}

/** 用户定时配置 */
export interface ScheduleConfig {
  enabled: boolean;
  turnOnTime: HourMinute; // 默认 07:50
  turnOffTime: HourMinute; // 默认 17:30
}

/** 屏幕控制原生模块接口（由原生侧实现） */
export interface ScreenControlModule {
  turnScreenOn: () => Promise<void>;
  turnScreenOff: () => Promise<void>;
  isScreenOn: () => Promise<boolean>;
}

/** 调度器下一次动作类型 */
export type ScheduleActionType = 'turnOn' | 'turnOff' | 'none';

/** 倒计时信息 */
export interface CountdownInfo {
  action: ScheduleActionType;
  /** 距离下一次动作的毫秒数（已归零表示立即执行） */
  remainingMs: number;
  /** 人类可读字符串，例如 "02 时 15 分 30 秒" */
  formatted: string;
  /** 目标 HourMinute */
  target: HourMinute | null;
}

/** 调度器运行时状态 */
export interface SchedulerRuntimeState {
  config: ScheduleConfig;
  countdown: CountdownInfo;
  screenOn: boolean;
  lastTriggered: ScheduleActionType | null;
}

/** 默认配置常量 */
export const DEFAULT_SCHEDULE_CONFIG: ScheduleConfig = {
  enabled: false,
  turnOnTime: {hour: 7, minute: 50},
  turnOffTime: {hour: 17, minute: 30},
};

/** 校验 HourMinute 合法性 */
export function isValidHourMinute(value: unknown): value is HourMinute {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.hour === 'number' &&
    typeof candidate.minute === 'number' &&
    candidate.hour >= 0 &&
    candidate.hour <= 23 &&
    candidate.minute >= 0 &&
    candidate.minute <= 59 &&
    Number.isInteger(candidate.hour) &&
    Number.isInteger(candidate.minute)
  );
}
