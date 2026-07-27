// Run this with: node sync_android_assets.js
// Copies index.html into the Android app's assets and swaps the CDN Tailwind/Google-Fonts
// references for the locally-bundled copies (tailwind.js + fonts/inter.css), so the Android
// app renders instantly instead of needing a network round-trip just to look styled.
// The source index.html itself (the actual website) is never touched — it keeps using the CDN.
const fs = require('fs');
const path = require('path');

const root = __dirname;
const src = path.join(root, 'index.html');
const dest = path.join(root, 'android/app/src/main/assets/www/index.html');

let html = fs.readFileSync(src, 'utf8');

html = html.replace(
  '<script src="https://cdn.tailwindcss.com"></script>',
  '<script src="tailwind.js"></script>'
);

const oldFonts = '  <link rel="preconnect" href="https://fonts.googleapis.com" />\n  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />\n  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet" />';
const newFonts = '  <link rel="stylesheet" href="fonts/inter.css" />';

if (html.includes(oldFonts)) {
  html = html.replace(oldFonts, newFonts);
} else if (!html.includes('fonts/inter.css')) {
  console.warn('WARNING: Google Fonts CDN link block not found as expected — check manually that fonts/inter.css is wired in.');
}

fs.writeFileSync(dest, html);
console.log('Synced -> ' + dest);
