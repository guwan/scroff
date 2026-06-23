/**
 * HomeScreen - 主屏
 *  - 顶部状态徽章
 *  - 调度总开关
 *  - 开屏时间 / 关屏时间选择
 *  - 手动控制按钮
 */
import React, {useCallback} from 'react';
import {Platform, ScrollView, Text, View} from 'react-native';
import {ActionButton, ScheduleCard} from '@components/ScheduleCard';
import {StatusBadge} from '@components/StatusBadge';
import {TimePicker} from '@components/TimePicker';
import {useScheduler} from '@hooks/useScheduler';
import {useScreenControl} from '@hooks/useScreenControl';

function pad(n: number): string {
  return n.toString().padStart(2, '0');
}

function formatHM(hm: {hour: number; minute: number}): string {
  return `${pad(hm.hour)}:${pad(hm.minute)}`;
}

export const HomeScreen: React.FC = () => {
  const scheduler = useScheduler();
  const screen = useScreenControl();

  const onTurnOn = useCallback(async () => {
    await screen.turnOn();
  }, [screen]);

  const onTurnOff = useCallback(async () => {
    await screen.turnOff();
  }, [screen]);

  return (
    <View className="flex-1 bg-gray-900">
      <ScrollView
        contentContainerStyle={{padding: 16, paddingBottom: 32}}
        keyboardShouldPersistTaps="handled">
        <View className="mb-4">
          <Text className="text-white text-2xl font-bold">屏幕定时开关</Text>
          <Text className="text-gray-400 text-sm mt-1">
            平台：{Platform.OS === 'android' ? 'Android' : Platform.OS === 'windows' ? 'Windows' : Platform.OS}
          </Text>
        </View>

        <StatusBadge
          screenOn={scheduler.screenOn}
          countdown={scheduler.countdown}
          lastTriggered={scheduler.lastTriggered}
        />

        <ScheduleCard
          title="启用定时"
          subtitle={
            scheduler.config.enabled
              ? `开屏 ${formatHM(scheduler.config.turnOnTime)} · 关屏 ${formatHM(scheduler.config.turnOffTime)}`
              : '关闭后定时不再触发'
          }
          enabled={scheduler.config.enabled}
          onToggle={next => void scheduler.setEnabled(next)}
        />

        <Text className="text-gray-300 text-sm mb-2 mt-2">开屏时间</Text>
        <TimePicker
          label="每天屏幕自动点亮的时间"
          value={scheduler.config.turnOnTime}
          onChange={next => void scheduler.setTurnOnTime(next.hour, next.minute)}
        />

        <Text className="text-gray-300 text-sm mb-2 mt-2">关屏时间</Text>
        <TimePicker
          label="每天屏幕自动熄灭的时间"
          value={scheduler.config.turnOffTime}
          onChange={next => void scheduler.setTurnOffTime(next.hour, next.minute)}
        />

        <View className="bg-gray-800 rounded-2xl p-4 mb-3">
          <Text className="text-white text-base font-semibold mb-3">手动控制</Text>
          <View className="flex-row gap-3">
            <View className="flex-1">
              <ActionButton
                label="点亮屏幕"
                variant="primary"
                onPress={onTurnOn}
                disabled={screen.busy}
              />
            </View>
            <View className="flex-1">
              <ActionButton
                label="关闭屏幕"
                variant="danger"
                onPress={onTurnOff}
                disabled={screen.busy}
              />
            </View>
          </View>
          {screen.error !== null ? (
            <Text className="text-red-400 text-xs mt-3">
              错误：{screen.error.message}
            </Text>
          ) : null}
        </View>

        <ActionButton
          label="恢复默认配置（07:50 / 17:30）"
          variant="secondary"
          onPress={() => void scheduler.resetConfig()}
        />

        <Text className="text-gray-500 text-xs text-center mt-6">
          定时精度：±30 秒 · 关闭 App 后由后台服务保持运行
        </Text>
      </ScrollView>
    </View>
  );
};

export default HomeScreen;
