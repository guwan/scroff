/**
 * App 根组件（同时支持 native 和 web）
 * Native 平台：从 index.js 加载
 * Web 平台：从 index.web.js 加载
 *
 *  - web：使用 react-native-web + Tailwind CDN（CSS 由 HTML 加载）
 *  - native：使用 NativeWind（CSS 在 index.js 顶层 require）
 */
import React from 'react';
import {SafeAreaView, StatusBar, StyleSheet} from 'react-native';
import {HomeScreen} from '@screens/HomeScreen';

const App: React.FC = () => {
  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#111827" />
      <HomeScreen />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#111827',
  },
});

export default App;
