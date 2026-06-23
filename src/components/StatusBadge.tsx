/**
 * StatusBadge - 状态徽章（显示当前屏幕状态 / 倒计时 / 上次触发动作）
 */
import React from 'react';
import {Text, View} from 'react-native';
import type {CountdownInfo, ScheduleActionType} from '@app-types/index';

interface StatusBadgeProps {
  screenOn: boolean;
  countdown: CountdownInfo;
  lastTriggered: ScheduleActionType | null;
}

function actionLabel(action: ScheduleActionType): string {
  switch (action) {
    case 'turnOn':
      return '开屏';
    case 'turnOff':
      return '关屏';
    case 'none':
      return '无';
    default:
      return '无';
  }
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({screenOn, countdown, lastTriggered}) => {
  const screenBadge = (
    <View
      className={`px-3 py-1 rounded-full ${
        screenOn ? 'bg-green-500/20' : 'bg-red-500/20'
      }`}>
      <Text className={`text-sm font-medium ${screenOn ? 'text-green-300' : 'text-red-300'}`}>
        屏幕 {screenOn ? '已点亮' : '已熄灭'}
      </Text>
    </View>
  );

  const countdownBadge = (
    <View className="px-3 py-1 rounded-full bg-blue-500/20 mt-2 self-start">
      <Text className="text-sm text-blue-300">
        下一次：{actionLabel(countdown.action)} · {countdown.formatted}
      </Text>
    </View>
  );

  const lastBadge =
    lastTriggered !== null ? (
      <View className="px-3 py-1 rounded-full bg-gray-700 mt-2 self-start">
        <Text className="text-xs text-gray-300">
          上次执行：{actionLabel(lastTriggered)}
        </Text>
      </View>
    ) : null;

  return (
    <View className="bg-gray-800 rounded-2xl p-4 mb-3">
      <Text className="text-white text-lg font-semibold">当前状态</Text>
      <View className="mt-2 flex-row items-center">{screenBadge}</View>
      {countdownBadge}
      {lastBadge}
    </View>
  );
};

export default StatusBadge;
