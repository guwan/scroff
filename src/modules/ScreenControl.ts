/**
 * Native Module - ScreenControl
 * JS 接口层：根据 Platform.OS 转发到 android / windows 原生实现
 *
 * 原生侧要求：
 *  - Android：注册名为 "ScreenControl" 的 ReactContextBaseJavaModule
 *    方法签名：turnScreenOn() / turnScreenOff() / isScreenOn() -> Promise
 *  - Windows：注册同名 C# 模块，方法签名同上
 */
import {NativeModules, Platform} from 'react-native';
import type {ScreenControlModule} from '@app-types/index';

/** 平台能力降级：未实现时给出友好的降级路径 */
const Fallback: ScreenControlModule = {
  async turnScreenOn(): Promise<void> {
    // eslint-disable-next-line no-console
    console.warn(
      `[ScreenControl] 原生模块未链接 (${Platform.OS})。请按 README 完成 Android/Windows 原生实现。`,
    );
  },
  async turnScreenOff(): Promise<void> {
    // eslint-disable-next-line no-console
    console.warn(
      `[ScreenControl] 原生模块未链接 (${Platform.OS})。请按 README 完成 Android/Windows 原生实现。`,
    );
  },
  async isScreenOn(): Promise<boolean> {
    return true;
  },
};

const Native = (NativeModules.ScreenControl as Partial<ScreenControlModule> | undefined) ??
  undefined;

function pickImpl(): ScreenControlModule {
  if (Native && typeof Native.turnScreenOn === 'function') {
    return Native as ScreenControlModule;
  }
  return Fallback;
}

export const ScreenControl: ScreenControlModule = {
  turnScreenOn: (): Promise<void> => pickImpl().turnScreenOn(),
  turnScreenOff: (): Promise<void> => pickImpl().turnScreenOff(),
  isScreenOn: (): Promise<boolean> => pickImpl().isScreenOn(),
};

export default ScreenControl;
