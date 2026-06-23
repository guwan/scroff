const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');

/**
 * Webpack 配置 - 用于浏览器预览 react-native-web
 * 入口：index.web.js
 * 关键 alias：react-native -> react-native-web
 * CSS：Tailwind 通过 PostCSS 在构建期处理
 */
module.exports = (env, argv) => {
  const isDev = argv.mode !== 'production';

  return {
    mode: isDev ? 'development' : 'production',
    devtool: isDev ? 'eval-source-map' : 'source-map',
    entry: path.resolve(__dirname, 'index.web.js'),
    output: {
      path: path.resolve(__dirname, 'web/dist'),
      filename: 'bundle.js',
      publicPath: isDev ? '/' : './',
      clean: true,
    },
    resolve: {
      extensions: ['.web.jsx', '.web.js', '.web.tsx', '.web.ts', '.js', '.jsx', '.ts', '.tsx', '.json'],
      alias: {
        'react-native': 'react-native-web',
        '@': path.resolve(__dirname, 'src'),
        '@components': path.resolve(__dirname, 'src/components'),
        '@modules': path.resolve(__dirname, 'src/modules'),
        '@services': path.resolve(__dirname, 'src/services'),
        '@screens': path.resolve(__dirname, 'src/screens'),
        '@hooks': path.resolve(__dirname, 'src/hooks'),
        '@app-types': path.resolve(__dirname, 'src/types'),
      },
    },
    module: {
      rules: [
        {
          test: /\.(js|jsx|ts|tsx)$/,
          exclude: /node_modules\/(?!(react-native|react-native-web)\/).*/,
          use: {
            loader: 'babel-loader',
            options: {
              babelrc: false,
              configFile: path.resolve(__dirname, 'babel.webpack.config.js'),
            },
          },
        },
        {
          test: /\.css$/,
          use: [
            isDev ? 'style-loader' : MiniCssExtractPlugin.loader,
            {
              loader: 'css-loader',
              options: {importLoaders: 1},
            },
            'postcss-loader',
          ],
        },
      ],
    },
    plugins: [
      new HtmlWebpackPlugin({
        template: path.resolve(__dirname, 'web/index.html'),
        inject: 'body',
      }),
      ...(!isDev
        ? [new MiniCssExtractPlugin({filename: 'styles.css'})]
        : []),
    ],
    devServer: {
      static: {directory: path.resolve(__dirname, 'web/dist')},
      port: 3000,
      host: '0.0.0.0',
      historyApiFallback: true,
      hot: true,
      open: false,
    },
    performance: {hints: false},
  };
};
