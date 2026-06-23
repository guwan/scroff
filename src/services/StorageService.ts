/**
 * AsyncStorage 持久化服务
 * 负责读写 ScheduleConfig
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  DEFAULT_SCHEDULE_CONFIG,
  HourMinute,
  isValidHourMinute,
  ScheduleConfig,
} from '@app-types/index';

const STORAGE_KEY = '@ScroffScreen:scheduleConfig:v1';

function isValidScheduleConfig(value: unknown): value is ScheduleConfig {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const v = value as Record<string, unknown>;
  return (
    typeof v.enabled === 'boolean' &&
    isValidHourMinute(v.turnOnTime) &&
    isValidHourMinute(v.turnOffTime)
  );
}

export const StorageService = {
  async loadConfig(): Promise<ScheduleConfig> {
    try {
      const raw = await AsyncStorage.getItem(STORAGE_KEY);
      if (raw === null) {
        return DEFAULT_SCHEDULE_CONFIG;
      }
      const parsed: unknown = JSON.parse(raw);
      if (isValidScheduleConfig(parsed)) {
        return parsed;
      }
      // 数据不合法时回退到默认
      return DEFAULT_SCHEDULE_CONFIG;
    } catch (error) {
      // 解析失败：保留默认配置，避免崩溃
      // eslint-disable-next-line no-console
      console.warn('[StorageService] loadConfig failed, fallback to default', error);
      return DEFAULT_SCHEDULE_CONFIG;
    }
  },

  async saveConfig(config: ScheduleConfig): Promise<void> {
    try {
      await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(config));
    } catch (error) {
      // eslint-disable-next-line no-console
      console.error('[StorageService] saveConfig failed', error);
      throw error;
    }
  },

  async clearConfig(): Promise<void> {
    await AsyncStorage.removeItem(STORAGE_KEY);
  },
};

export type {HourMinute};
