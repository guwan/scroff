/**
 * useScheduler - 定时调度核心 Hook
 * - 加载/保存 ScheduleConfig
 * - 每秒刷新倒计时
 * - 到达时间后调用 ScreenControl 执行开/关屏
 */
import {useCallback, useEffect, useRef, useState} from 'react';
import {
  computeNextAction,
  executeScreenAction,
  formatCountdown,
} from '@services/SchedulerService';
import {StorageService} from '@services/StorageService';
import {
  CountdownInfo,
  DEFAULT_SCHEDULE_CONFIG,
  ScheduleActionType,
  ScheduleConfig,
} from '@app-types/index';
import {useScreenControl} from './useScreenControl';

const TICK_MS = 1000;
const EXECUTE_TOLERANCE_MS = 30 * 1000; // ±30 秒容差

export interface UseSchedulerResult {
  config: ScheduleConfig;
  countdown: CountdownInfo;
  loading: boolean;
  lastTriggered: ScheduleActionType | null;
  screenOn: boolean;
  busy: boolean;
  error: Error | null;
  setEnabled: (enabled: boolean) => Promise<void>;
  setTurnOnTime: (hour: number, minute: number) => Promise<void>;
  setTurnOffTime: (hour: number, minute: number) => Promise<void>;
  resetConfig: () => Promise<void>;
}

export function useScheduler(): UseSchedulerResult {
  const [config, setConfig] = useState<ScheduleConfig>(DEFAULT_SCHEDULE_CONFIG);
  const [loading, setLoading] = useState<boolean>(true);
  const [now, setNow] = useState<Date>(new Date());
  const [lastTriggered, setLastTriggered] = useState<ScheduleActionType | null>(null);
  const lastExecutedRef = useRef<{action: ScheduleActionType; at: number} | null>(null);

  const screen = useScreenControl();

  // 初次加载配置
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const stored = await StorageService.loadConfig();
      if (!cancelled) {
        setConfig(stored);
        setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // 每秒 tick
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), TICK_MS);
    return () => clearInterval(id);
  }, []);

  const persist = useCallback(async (next: ScheduleConfig) => {
    setConfig(next);
    await StorageService.saveConfig(next);
  }, []);

  const setEnabled = useCallback(
    async (enabled: boolean) => {
      await persist({...config, enabled});
    },
    [config, persist],
  );

  const setTurnOnTime = useCallback(
    async (hour: number, minute: number) => {
      await persist({...config, turnOnTime: {hour, minute}});
    },
    [config, persist],
  );

  const setTurnOffTime = useCallback(
    async (hour: number, minute: number) => {
      await persist({...config, turnOffTime: {hour, minute}});
    },
    [config, persist],
  );

  const resetConfig = useCallback(async () => {
    await persist(DEFAULT_SCHEDULE_CONFIG);
  }, [persist]);

  // 触发检测
  useEffect(() => {
    const next = computeNextAction(config, now);
    if (next.action === 'none') {
      return;
    }
    if (next.remainingMs > EXECUTE_TOLERANCE_MS) {
      return;
    }
    // 防止重复触发：同 action 在 60 秒内仅触发一次
    const last = lastExecutedRef.current;
    if (last && last.action === next.action && now.getTime() - last.at < 60 * 1000) {
      return;
    }
    lastExecutedRef.current = {action: next.action, at: now.getTime()};
    setLastTriggered(next.action);
    void executeScreenAction(next.action).catch(() => {
      // 错误已记录，不影响 UI
    });
  }, [config, now]);

  const next = computeNextAction(config, now);
  const countdown: CountdownInfo = {
    action: next.action,
    remainingMs: next.remainingMs,
    formatted: formatCountdown(next.remainingMs),
    target: next.target,
  };

  return {
    config,
    countdown,
    loading,
    lastTriggered,
    screenOn: screen.screenOn,
    busy: screen.busy,
    error: screen.error,
    setEnabled,
    setTurnOnTime,
    setTurnOffTime,
    resetConfig,
  };
}

export default useScheduler;
