import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'de.pigcloud.app',
  appName: 'PigCloud',
  webDir: 'www',
  server: {
    url: 'https://pigcloud.de/cloud/',
    cleartext: false,
    errorPath: 'error.html',
  },
  android: {
    appendUserAgent: 'PigCloudApp',
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: false,
  },
  ios: {
    appendUserAgent: 'PigCloudApp',
    contentInset: 'never',
    allowsLinkPreview: false,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    SplashScreen: {
      launchAutoHide: true,
      launchShowDuration: 0,
    },
    StatusBar: {
      style: 'DARK',
      backgroundColor: '#121212',
    },
    BiometricAuth: {
      androidBiometryStrength: 'weak',
    },
  },
};

export default config;
