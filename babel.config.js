module.exports = {
  presets: [
    ['@react-native/babel-preset', {unstable_transformProfile: 'hermes-stable'}],
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
  ],
};
