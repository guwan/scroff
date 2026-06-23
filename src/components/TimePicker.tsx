/**
 * TimePicker - 时分选择器
 * 兼容跨平台（无第三方时间选择器依赖，Android/Windows 通用）
 */
import React, {useCallback, useMemo, useState} from 'react';
import {Pressable, ScrollView, Text, View} from 'react-native';
import {HourMinute} from '@app-types/index';

interface TimePickerProps {
  value: HourMinute;
  onChange: (next: HourMinute) => void;
  label?: string;
}

const HOURS: number[] = Array.from({length: 24}, (_, i) => i);
const MINUTES: number[] = Array.from({length: 60}, (_, i) => i);

const ITEM_HEIGHT = 40;

function pad(n: number): string {
  return n.toString().padStart(2, '0');
}

function adjust<T>(arr: T[], idx: number): T {
  if (idx < 0) {
    return arr[0];
  }
  if (idx >= arr.length) {
    return arr[arr.length - 1];
  }
  return arr[idx];
}

export const TimePicker: React.FC<TimePickerProps> = ({value, onChange, label}) => {
  const [hour, setHour] = useState<number>(value.hour);
  const [minute, setMinute] = useState<number>(value.minute);

  const commit = useCallback(
    (h: number, m: number) => {
      onChange({hour: h, minute: m});
    },
    [onChange],
  );

  const hourColumn = useMemo(
    () => (
      <ScrollColumn
        items={HOURS.map(h => pad(h))}
        selectedIndex={hour}
        onSelect={idx => {
          const next = adjust(HOURS, idx);
          setHour(next);
          commit(next, minute);
        }}
      />
    ),
    [hour, minute, commit],
  );

  const minuteColumn = useMemo(
    () => (
      <ScrollColumn
        items={MINUTES.map(m => pad(m))}
        selectedIndex={minute}
        onSelect={idx => {
          const next = adjust(MINUTES, idx);
          setMinute(next);
          commit(hour, next);
        }}
      />
    ),
    [hour, minute, commit],
  );

  return (
    <View className="bg-gray-800 rounded-2xl p-4 mb-3">
      {label !== undefined ? (
        <Text className="text-gray-300 text-sm mb-3">{label}</Text>
      ) : null}
      <View className="flex-row items-center justify-center">
        <View className="flex-1">{hourColumn}</View>
        <Text className="text-gray-400 text-2xl mx-2">:</Text>
        <View className="flex-1">{minuteColumn}</View>
      </View>
    </View>
  );
};

interface ScrollColumnProps {
  items: string[];
  selectedIndex: number;
  onSelect: (idx: number) => void;
}

const ScrollColumn: React.FC<ScrollColumnProps> = ({items, selectedIndex, onSelect}) => {
  return (
    <View
      className="h-40 bg-gray-900 rounded-xl overflow-hidden"
      style={{height: ITEM_HEIGHT * 3}}>
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{paddingVertical: ITEM_HEIGHT}}
        snapToInterval={ITEM_HEIGHT}
        decelerationRate="fast"
        onMomentumScrollEnd={e => {
          const offsetY = e.nativeEvent.contentOffset.y;
          const idx = Math.round(offsetY / ITEM_HEIGHT);
          onSelect(idx);
        }}>
        {items.map((it, idx) => {
          const active = idx === selectedIndex;
          return (
            <Pressable
              key={`${it}-${idx}`}
              onPress={() => onSelect(idx)}
              style={{height: ITEM_HEIGHT}}
              className={`items-center justify-center ${
                active ? 'bg-blue-500/20' : ''
              }`}>
              <Text
                className={`text-xl ${active ? 'text-blue-400 font-bold' : 'text-gray-400'}`}>
                {it}
              </Text>
            </Pressable>
          );
        })}
      </ScrollView>
    </View>
  );
};

export default TimePicker;
