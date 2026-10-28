import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { copyWebDist } from './build-web.mjs'

test('复制 Web dist 到 Tauri 资源目录', async () => {
  const root = await mkdtemp(join(tmpdir(), 'phantom-im-pc-'))
  const webRoot = join(root, 'web')
  const resourceRoot = join(root, 'resources/web')
  await mkdir(join(webRoot, 'dist/assets'), { recursive: true })
  await writeFile(join(webRoot, 'dist/index.html'), '<html></html>')
  await writeFile(join(webRoot, 'dist/assets/app.js'), 'console.log(1)')

  await copyWebDist({ webRoot, resourceRoot })

  const copied = await import('node:fs/promises').then(({ readFile }) =>
    readFile(join(resourceRoot, 'assets/app.js'), 'utf8'))
  assert.equal(copied, 'console.log(1)')
})

test('缺少 Web dist 时复制失败', async () => {
  const root = await mkdtemp(join(tmpdir(), 'phantom-im-pc-'))
  await assert.rejects(
    copyWebDist({ webRoot: join(root, 'missing-web'), resourceRoot: join(root, 'resources/web') }),
    /ENOENT/,
  )
})
