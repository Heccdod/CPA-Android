# 发布状态

已发布：https://github.com/Heccdod/CPA-Android/releases/tag/v2.1.0

## 已上线的东西

| 内容 | 位置 |
| :--- | :--- |
| v2.1.0 正式包 | https://github.com/Heccdod/CPA-Android/releases/tag/v2.1.0 |
| CI 自动同步流水线 | `.github/workflows/auto-sync-upstream.yml`，每 6 小时对一次上游 |
| 安装说明 | README.md |

## token

GitHub 的 device-flow token 存在 `/root/.cpa-gh-token`（权限 600，scope `repo workflow`）。
失效后用 `bash scripts/gh-device-login.sh` 重新授权。

## 下次发版

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`
2. `./gradlew assembleRelease assembleDebug`
3. `git tag -a vX.Y.Z -m 'CPA-Android X.Y.Z' && git push origin main vX.Y.Z`
4. 到 Releases 页面基于该 tag 建 release，上传 release APK、debug APK、裸内核 .so、SHA256SUMS.txt

## 两条护栏

- **CI 的 release 必须带 `--prerelease`**。App 读的是 `releases/latest`，普通 release 一创建就会顶掉正式版，
  更新检查会永远提示有新版本。已固化在 workflow 里。
- **资产名规则**：内核名含 `libgojni` 且以 `.so` 结尾才会被热更新识别；含 `debug` 的 APK 会被跳过，
  避免用调试包覆盖已签名正式包。
