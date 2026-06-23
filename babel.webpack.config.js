/**
 * Webpack 构建专用 babel 配置
 * 与 babel.config.js (原生) 解耦
 *
 * 关键：把 TS 类型剥离放到 plugins 数组中，plugin 一定先于 preset 执行
 * 见 https://babeljs.io/docs/options#plugins
 */
module.exports = {
  presets: [
    ['@babel/preset-env', {targets: {esmodules: true}}],
    ['@babel/preset-react', {runtime: 'automatic'}],
    'nativewind/babel',
  ],
  plugins: [
    [
      'module-resolver',
      {
        root: ['./src'],
        alias: {
          '@': './src',
          '@components': './src/components',
          '@modules': './src/modules',
          '@services': './src/services',
          '@screens': './src/screens',
          '@hooks': './src/hooks',
          '@app-types': './src/types',
        },
      },
    ],
    '@babel/plugin-transform-typescript',
  ],
};
