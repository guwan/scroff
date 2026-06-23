/**
 * Web 入口（webpack 使用）
 * - 加载 global.css（Tailwind 由 PostCSS 在 webpack 中处理）
 * - 注册组件并启动 runApplication
 */
import './global.css';
import {AppRegistry} from 'react-native';
import App from './App';
import appJson from './app.json';

const appName = appJson.name;

AppRegistry.registerComponent(appName, () => App);

const rootTag = document.getElementById('root');
if (rootTag) {
  AppRegistry.runApplication(appName, {rootTag});
}
