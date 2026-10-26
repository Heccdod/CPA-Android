# 推送到 GitHub

本机没有 GitHub 凭据（`gh` 未安装、无 token、无 SSH key），所以这两条 commit 与 v2.1.0 tag
还没有推出去。仓库已经就绪：remote 指向 `Heccdod/CPA-Android`，历史干净地接在 `Initial commit` 之上。

## 1. 推送代码与 tag

```bash
cd /root/AgyAndriod
git push https://<YOUR_TOKEN>@github.com/Heccdod/CPA-Android.git main
git push https://<YOUR_TOKEN>@github.com/Heccdod/CPA-Android.git v2.1.0
```

token 在 https://github.com/settings/tokens 生成，勾 `repo` 即可。

## 2. 发布 Release

资产已经构建好放在 `dist/`：

```
dist/CPA-Android-2.1.0.apk        33 MB   release，优先装这个
dist/CPA-Android-2.1.0-debug.apk  36 MB   调试用，签名与 release 一致
dist/libgojni-v2.1.0.so           90 MB   裸内核，供热更新使用
dist/SHA256SUMS.txt                      校验和
dist/RELEASE_NOTES.md                    发布说明
```

网页操作：仓库 → Releases → Draft a new release → 选 tag `v2.1.0` → 粘贴 RELEASE_NOTES.md
→ 上传 dist/ 里除 RELEASE_NOTES.md 外的四个文件 → Publish。

或者装 `gh` 一条命令搞定：

```bash
gh release create v2.1.0 \
  dist/CPA-Android-2.1.0.apk \
  dist/CPA-Android-2.1.0-debug.apk \
  dist/libgojni-v2.1.0.so \
  dist/SHA256SUMS.txt \
  --title 'CPA-Android 2.1.0' \
  --notes-file dist/RELEASE_NOTES.md
```

## 3. tag 名要对上

App 里的更新检查认 `releases/latest` 的 `tag_name`，并且已经忽略 `v` 前缀，所以 `v2.1.0`
和 `2.1.0` 都行。资产名必须含 `libgojni.so` 才能被热更新识别，含 `debug` 的 APK 会被跳过。

## 4. 之后自动化

`.github/workflows/auto-sync-upstream.yml` 已经就位：每 6 小时对一次上游 head，变了就重编
AAR、跑测试、出包、发 Release。推上去之后这条流水线会自己接管后续版本。
