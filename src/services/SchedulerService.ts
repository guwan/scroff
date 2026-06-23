/**
 * 定时调度业务逻辑
 * 不直接依赖 React Hooks，可在任意上下文（UI、后台 Service、HeadlessJS）调用
 */
import {
  CountdownInfo,
  HourMinute,
  ScheduleActionType,
  ScheduleConfig,
} from '@app-types/index';
import {ScreenControl} from '@modules/ScreenControl';

function toDate(hourMinute: HourMinute, base: Date): Date {
  const next = new Date(base);
  next.setHours(hourMinute.hour, hourMinute.minute, 0, 0);
  return next;
}

/** 计算距离下一次动作的毫秒数与动作类型 */
export function computeNextAction(
  config: ScheduleConfig,
  now: Date = new Date(),
): {action: ScheduleActionType; target: HourMinute | null; remainingMs: number} {
  if (!config.enabled) {
    return {action: 'none', target: null, remainingMs: 0};
  }

  const onDate = toDate(config.turnOnTime, now);
  const offDate = toDate(config.turnOffTime, now);

  const candidates: Array<{
    action: ScheduleActionType;
    target: HourMinute;
    when: Date;
  }> = [];

  // 候选：今天的 onTime / offTime，以及明天的 onTime / offTime
  const pushCandidate = (
    action: ScheduleActionType,
    target: HourMinute,
    when: Date,
  ) => {
    if (when.getTime() > now.getTime()) {
      candidates.push({action, target, when});
    }
  };

  pushCandidate('turnOn', config.turnOnTime, onDate);
  pushCandidate('turnOff', config.turnOffTime, offDate);

  const tomorrowOn = new Date(onDate);
  tomorrowOn.setDate(tomorrowOn.getDate() + 1);
  pushCandidate('turnOn', config.turnOnTime, tomorrowOn);

  const tomorrowOff = new Date(offDate);
  tomorrowOff.setDate(tomorrowOff.getDate() + 1);
  pushCandidate('turnOff', config.turnOffTime, tomorrowOff);

  if (candidates.length === 0) {
    return {action: 'none', target: null, remainingMs: 0};
  }

  candidates.sort((a, b) => a.when.getTime() - b.when.getTime());
  const next = candidates[0];
  return {
    action: next.action,
    target: next.target,
    remainingMs: next.when.getTime() - now.getTime(),
  };
}

/** 人类可读倒计时（最高精度：秒） */
export function formatCountdown(remainingMs: number): string {
  if (remainingMs <= 0) {
    return '00 时 00 分 00 秒';
  }
  const totalSeconds = Math.floor(remainingMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${pad(hours)} 时 ${pad(minutes)} 分 ${pad(seconds)} 秒`;
}

/** 触发一次开/关屏动作，封装异步错误 */
export async function executeScreenAction(action: ScheduleActionType): Promise<void> {
  if (action === 'none') {
    return;
  }
  try {
    if (action === 'turnOn') {
      await ScreenControl.turnScreenOn();
    } else {
      await ScreenControl.turnScreenOff();
    }
  } catch (error) {
    // eslint-disable-next-line no-console
    console.error('[SchedulerService] executeScreenAction failed', action, error);
    throw error;
  }
}

export type {CountdownInfo};
