/**
 * ScheduleCard - 调度卡片（开关 + 标题 + 副标题）
 */
import React from 'react';
import {Pressable, Switch, Text, View} from 'react-native';

interface ScheduleCardProps {
  title: string;
  subtitle?: string;
  enabled: boolean;
  onToggle: (next: boolean) => void;
  children?: React.ReactNode;
}

export const ScheduleCard: React.FC<ScheduleCardProps> = ({
  title,
  subtitle,
  enabled,
  onToggle,
  children,
}) => {
  return (
    <View className="bg-gray-800 rounded-2xl p-4 mb-3">
      <View className="flex-row items-center justify-between">
        <View className="flex-1 pr-3">
          <Text className="text-white text-base font-semibold">{title}</Text>
          {subtitle !== undefined ? (
            <Text className="text-gray-400 text-sm mt-1">{subtitle}</Text>
          ) : null}
        </View>
        <Switch
          value={enabled}
          onValueChange={onToggle}
          trackColor={{false: '#374151', true: '#3b82f6'}}
          thumbColor={enabled ? '#dbeafe' : '#9ca3af'}
        />
      </View>
      {children !== undefined ? <View className="mt-3">{children}</View> : null}
    </View>
  );
};

interface ActionButtonProps {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'danger';
  disabled?: boolean;
}

export const ActionButton: React.FC<ActionButtonProps> = ({
  label,
  onPress,
  variant = 'primary',
  disabled = false,
}) => {
  const palette = (() => {
    switch (variant) {
      case 'primary':
        return 'bg-blue-500 active:bg-blue-600';
      case 'secondary':
        return 'bg-gray-700 active:bg-gray-600';
      case 'danger':
        return 'bg-red-500 active:bg-red-600';
      default:
        return 'bg-blue-500 active:bg-blue-600';
    }
  })();

  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      className={`${palette} ${disabled ? 'opacity-50' : ''} rounded-xl py-3 px-5 items-center`}>
      <Text className="text-white text-base font-semibold">{label}</Text>
    </Pressable>
  );
};

export default ScheduleCard;
