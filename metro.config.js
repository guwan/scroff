const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');
const {withNativeWind} = require('nativewind/metro');

/**
 * Metro configuration - 原生平台（Android/Windows）专用
 * Web 平台走 webpack（webpack.config.js）
 *
 * @type {import('metro-config').MetroConfig}
 */
const projectRoot = __dirname;

const config = {
  resolver: {
    sourceExts: ['js', 'jsx', 'json', 'ts', 'tsx'],
  },
};

module.exports = withNativeWind(mergeConfig(getDefaultConfig(projectRoot), config), {
  input: './global.css',
});
