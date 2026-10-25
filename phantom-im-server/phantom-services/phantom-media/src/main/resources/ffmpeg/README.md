# FFmpeg 工具包资源约定

仓库默认不提交第三方二进制。执行 Maven `generate-resources`（`compile/package` 都会触发）时由
`phantom-im-server/scripts/fetch-ffmpeg-tools.xml` 从固定版本
`eugeneware/ffmpeg-static b6.1.1` 下载并校验四个平台的 FFmpeg 资产，然后在
`src/main/resources/ffmpeg` 直接生成以下 zip，并由默认 resources 流程打入同一个 jar：

```text
ffmpeg/
  macos/aarch64/ffmpeg-tools.zip
  linux/x86_64/ffmpeg-tools.zip
  linux/aarch64/ffmpeg-tools.zip
  windows/x86_64/ffmpeg-tools.zip
```

每个 zip 根目录包含 `ffmpeg`、`ffprobe`、`LICENSE` 和 `SOURCE.txt`；Windows 使用 `ffmpeg.exe` 与 `ffprobe.exe`。

目标 `ffmpeg-tools.zip` 已存在且非空时不会下载，也不会重新生成。构建时在目标目录的
`.download-*` 临时目录中下载、校验并组装；成功后删除临时目录。下载或校验失败时构建直接失败。

首次打包需要 Node.js 18+、`curl` 和访问 GitHub Release 的网络。内网构建可设置
`FFMPEG_TOOLS_BASE_URL` 指向固定版本资产的镜像地址；脚本仍会按内置 SHA-256 校验，
镜像内容不一致时构建失败。

GitHub 直连较慢时可配置 HTTP 代理：

```bash
FFMPEG_TOOLS_PROXY=http://127.0.0.1:7897 mvn package
```

下载使用 `curl --continue-at -`，中断后会复用 `.download/` 中的部分文件继续下载。

运行时只释放当前 `os.name + os.arch` 对应的一个目录到 `phantom.media.tool-root`。版本目录通过 `.ready` 标记和资源 SHA-256 校验，已释放且完整时不会重复写入。

建议：

- 默认发布产物是一个 jar 同时包含 `windows/x86_64`、`macos/aarch64`、`linux/x86_64`、`linux/aarch64` 四套工具包，启动时只释放当前平台。
- 一个 jar 会增加约四套压缩工具的体积；标准发布包保持四平台完整。
- `tool-root` 挂载持久卷，容器升级不必每次释放。
- 发布前核对 FFmpeg 构建选项、CPU 基线、许可证（LGPL/GPL）和 NOTICE 文件。
