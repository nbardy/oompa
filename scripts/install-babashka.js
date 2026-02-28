#!/usr/bin/env node

const os = require('os');
const fs = require('fs');
const path = require('path');
const https = require('https');
const { execSync } = require('child_process');

const BB_VERSION = '1.3.191'; // A stable release version

// Map Node.js os/arch to Babashka release asset names
const platforms = {
  darwin: {
    arm64: `babashka-${BB_VERSION}-macos-aarch64.tar.gz`,
    x64: `babashka-${BB_VERSION}-macos-amd64.tar.gz`,
  },
  linux: {
    arm64: `babashka-${BB_VERSION}-linux-aarch64-static.tar.gz`,
    x64: `babashka-${BB_VERSION}-linux-amd64-static.tar.gz`,
  },
  // We can add Windows later if needed, but it requires zip extraction
};

async function downloadBabashka() {
  const osType = os.platform();
  const arch = os.arch();

  // If already installed globally and accessible, we can skip downloading!
  try {
    const existing = execSync('which bb', { encoding: 'utf-8' }).trim();
    if (existing) {
      console.log(`[oompa] System Babashka found at ${existing}. Skipping download.`);
      return;
    }
  } catch (e) {
    // Not found, continue with download
  }

  const assetName = platforms[osType]?.[arch];
  if (!assetName) {
    console.warn(`[oompa] Automatic Babashka download not supported for ${osType} ${arch}.`);
    console.warn('[oompa] Please install manually: https://github.com/babashka/babashka#installation');
    return;
  }

  const downloadUrl = `https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/${assetName}`;
  const binDir = path.join(__dirname, '..', 'bin');
  const tempTar = path.join(binDir, 'bb.tar.gz');
  const bbDest = path.join(binDir, 'bb');

  if (!fs.existsSync(binDir)) {
    fs.mkdirSync(binDir, { recursive: true });
  }

  console.log(`[oompa] Downloading Babashka (native engine) from ${downloadUrl}...`);

  return new Promise((resolve, reject) => {
    // Handle GitHub's redirects (302)
    https.get(downloadUrl, (res) => {
      if (res.statusCode === 301 || res.statusCode === 302) {
        https.get(res.headers.location, (redirectRes) => {
          const fileStream = fs.createWriteStream(tempTar);
          redirectRes.pipe(fileStream);
          fileStream.on('finish', () => {
            fileStream.close();
            extract(tempTar, binDir, bbDest, resolve, reject);
          });
        }).on('error', reject);
      } else {
        reject(new Error(`Failed to download: ${res.statusCode}`));
      }
    }).on('error', reject);
  });
}

function extract(tarPath, destDir, bbDest, resolve, reject) {
  console.log('[oompa] Extracting native binary...');
  try {
    execSync(`tar -xzf ${tarPath} -C ${destDir}`);
    fs.unlinkSync(tarPath); // cleanup

    if (fs.existsSync(bbDest)) {
      fs.chmodSync(bbDest, 0o755); // Make it executable
      console.log('[oompa] ✅ Native engine installed successfully!');
      resolve();
    } else {
      reject(new Error('Extraction failed: bb binary not found.'));
    }
  } catch (e) {
    reject(e);
  }
}

downloadBabashka().catch((err) => {
  console.error(`[oompa] Failed to install Babashka natively: ${err.message}`);
  console.warn('[oompa] Please install manually: https://github.com/babashka/babashka#installation');
});
