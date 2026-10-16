// Rasterize the data card without modifying its SVG source.
const sharp = require(process.argv[4] || 'sharp');

async function main() {
  const [source, destination] = process.argv.slice(2);
  if (!source || !destination) throw new Error('Expected SVG source and PNG destination');
  await sharp(source).png({ compressionLevel: 9 }).toFile(destination);
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
