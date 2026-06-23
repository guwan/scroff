/**
 * useScreenControl - 封装屏幕开关能力
 */
import {useCallback, useEffect, useState} from 'react';
import {ScreenControl} from '@modules/ScreenControl';

export interface UseScreenControlResult {
  screenOn: boolean;
  busy: boolean;
  error: Error | null;
  turnOn: () => Promise<void>;
  turnOff: () => Promise<void>;
  refresh: () => Promise<void>;
}

export function useScreenControl(): UseScreenControlResult {
  const [screenOn, setScreenOn] = useState<boolean>(true);
  const [busy, setBusy] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);

  const refresh = useCallback(async () => {
    try {
      const value = await ScreenControl.isScreenOn();
      setScreenOn(value);
    } catch (e) {
      setError(e instanceof Error ? e : new Error(String(e)));
    }
  }, []);

  const turnOn = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      await ScreenControl.turnScreenOn();
      setScreenOn(true);
    } catch (e) {
      setError(e instanceof Error ? e : new Error(String(e)));
    } finally {
      setBusy(false);
    }
  }, []);

  const turnOff = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      await ScreenControl.turnScreenOff();
      setScreenOn(false);
    } catch (e) {
      setError(e instanceof Error ? e : new Error(String(e)));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return {screenOn, busy, error, turnOn, turnOff, refresh};
}

export default useScreenControl;
