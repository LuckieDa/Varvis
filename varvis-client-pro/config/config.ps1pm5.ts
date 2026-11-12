import { defineConfig } from 'umi';

// Only the isolated branch build opts into this configuration.
if (!process.env.VARVIS_PS1PM5_OUTPUT) {
  throw new Error('Use scripts/Build-Ps1Pm5Frontend.ps1 for the isolated build.');
}

export default defineConfig({
  outputPath: process.env.VARVIS_PS1PM5_OUTPUT,
  mfsu: false,
});
