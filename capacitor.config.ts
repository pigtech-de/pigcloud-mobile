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
  plugins: {
    SplashScreen: {
      launchAutoHide: true,
      launchShowDuration: 1500,
      androidScaleType: 'CENTER_CROP',
      backgroundColor: '#121212',
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
