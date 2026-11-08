// Original vector mark; render with Node.js and the sharp package.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const svg = fs.readFileSync(path.join(root, 'docs/assets/app-icon.svg'));
const sizes = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };

async function main() {
  await sharp(svg, { density: 384 }).resize(512, 512).png()
    .toFile(path.join(root, 'docs/assets/app-icon.png'));
  for (const [density, size] of Object.entries(sizes)) {
    const directory = path.join(root, 'app/src/main/res', `mipmap-${density}`);
    fs.mkdirSync(directory, { recursive: true });
    await sharp(svg, { density: 384 }).resize(size, size).png()
      .toFile(path.join(directory, 'ic_launcher.png'));
  }
  if (process.argv[2]) {
    await sharp(svg, { density: 384 }).resize(1024, 1024).png()
      .toFile(path.resolve(process.argv[2]));
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
