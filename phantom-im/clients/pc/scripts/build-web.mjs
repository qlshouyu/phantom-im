import { cp, mkdir, rm, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const pcRoot = resolve(scriptDir, '..')

export function webPaths(env = process.env) {
  return {
    webRoot: resolve(env.PHANTOM_IM_WEB_DIR ?? resolve(pcRoot, '../web')),
    resourceRoot: resolve(env.PHANTOM_IM_RESOURCE_DIR ?? resolve(pcRoot, 'src-tauri/resources/web')),
  }
}

export async function copyWebDist({ webRoot, resourceRoot }) {
  const dist = resolve(webRoot, 'dist')
  const info = await stat(dist)
  if (!info.isDirectory()) throw new Error(`Web dist 不是目录: ${dist}`)
  await stat(resolve(dist, 'index.html'))

  await rm(resourceRoot, { recursive: true, force: true })
  await mkdir(resourceRoot, { recursive: true })
  await cp(dist, resourceRoot, { recursive: true, force: true })
}

export async function buildWeb(env = process.env) {
  const paths = webPaths(env)
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  const result = spawnSync(npm, ['run', 'build'], {
    cwd: paths.webRoot,
    env,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`Web 构建失败，退出码 ${result.status ?? 'unknown'}`)
  await copyWebDist(paths)
  return paths.resourceRoot
}

async function main() {
  const resourceRoot = await buildWeb()
  console.log(`Phantom IM Web 资源已复制: ${resourceRoot}`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : error)
    process.exitCode = 1
  })
}
