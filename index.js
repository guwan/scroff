/**
 * @format
 *
 * Native 入口（Metro / 原生平台使用）
 * - 加载 NativeWind 全局样式
 * - 注册组件
 */
import './global.css';
import {AppRegistry} from 'react-native';
import App from './App';
import appJson from './app.json';

const appName = appJson.name;

AppRegistry.registerComponent(appName, () => App);
