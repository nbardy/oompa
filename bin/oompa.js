#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const packageRoot = path.resolve(__dirname, "..");
const swarmScript = path.join(packageRoot, "swarm.bb");
const classpath = path.join(packageRoot, "agentnet", "src");
const argv = process.argv.slice(2);

if (!fs.existsSync(swarmScript) || !fs.existsSync(classpath)) {
  console.error("oompa package installation is incomplete.");
  process.exit(1);
}

const result = spawnSync("bb", ["--classpath", classpath, swarmScript, ...argv], {
  stdio: "inherit",
  cwd: process.cwd(),
  env: process.env
});

if (result.error) {
  if (result.error.code === "ENOENT") {
    console.error("Babashka (bb) is required. Install: https://github.com/babashka/babashka");
  } else {
    console.error(`Failed to run bb: ${result.error.message}`);
  }
  process.exit(1);
}

if (typeof result.status === "number") {
  process.exit(result.status);
}

process.exit(1);
