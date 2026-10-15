import { readFileSync } from 'node:fs';
import { argbFromHex, hexFromArgb, TonalPalette } from '@material/material-color-utilities';

const tones = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100];
const palette = TonalPalette.fromInt(argbFromHex('#D92D4F'));
const generated = Object.fromEntries(tones.map(tone => [tone, hexFromArgb(palette.tone(tone)).toUpperCase()]));

if (process.argv.includes('--check')) {
  const theme = readFileSync(new URL('../android/app/src/main/java/com/huoyi/photovault/ui/theme/Theme.kt', import.meta.url), 'utf8');
  for (const [tone, hex] of Object.entries(generated)) {
    if (!theme.includes(`YingjiTone${tone} = Color(0xFF${hex.slice(1)})`)) throw new Error(`YingjiTone${tone} must be ${hex}`);
  }
  console.log('Yingji tones match Theme.kt');
} else {
  console.log(JSON.stringify(generated, null, 2));
}
