# 安装 CPA-Android

装包只需两条命令，装完直接能用，不需要电脑常开。

## 方式一：USB 数据线（最快）

WSL 里没有 adb 也没关系，用 Windows 那份：

```powershell
# Windows PowerShell，手机插上并允许 USB 调试
cd C:\Users\28381\Documents\ds_home\tools\platform-tools
.\adb.exe install -r C:\Users\28381\Desktop\CPA-Android-2.1.0.apk
```

看到 `Success` 就装好了。覆盖安装加 `-r`。

如果报 `could not read ok from ADB Server`，是 WSL 的 adb server 占着 5037 端口：

```bash
/opt/android-sdk/platform-tools/adb kill-server
```

然后在 Windows 侧重试。

## 方式二：手机上直接装（不用电脑）

把 `CPA-Android-2.1.0.apk` 传到手机（微信文件传输助手 / QQ 我的设备 / 数据线拷贝都行），
在文件管理器里点开安装。首次会提示「允许安装未知来源应用」，允许即可。

> 这个包用 debug key 签名（个人自用），所以：
> - 覆盖安装旧版本没问题
> - 换成别人机器编的包会报「签名冲突」，得先卸载旧版
> 卸载会清掉 `/data/data/me.routerfor.cpa/` 里的配置和已登录账号，重装后要重新授权。

## 装完之后

1. 打开 App，首页状态卡显示 **运行中** + `127.0.0.1:8317` 就代表网关起来了
2. 点「快捷添加」→ 选 Antigravity（或别的提供商）→ 跳 Chrome 登录 Google
3. 授权完成后浏览器自动回到 App，账号出现在首页「AI 提供商」卡里
4. 要用酒馆（SillyTavern 之类）：API Base 填 `http://127.0.0.1:8317`，密钥填首页那个
   `cpa-android-local`；如果酒馆跑在别的设备上，先在「设置 → 网络 → 允许局域网访问」打开，
   再填手机的局域网 IP

不用时网关跟着常驻通知活着；想彻底停掉就点通知里的「停止服务」。

## 构建产物在哪

| 文件 | 大小 | 说明 |
| :--- | ---: | :--- |
| `dist/CPA-Android-2.1.0.apk` | 33 MB | release 包，优先装这个 |
| `dist/CPA-Android-2.1.0-debug.apk` | 36 MB | 调试用，签名与 release 一致，可互装 |
| `dist/libgojni-v2.1.0.so` | 90 MB | 裸内核，供 App 内热更新 |

已复制一份到 Windows 桌面：`C:\Users\28381\Desktop\CPA-Android-2.1.0.apk`。

## 自己重新编

```bash
cd /root/AgyAndriod
./gradlew assembleRelease assembleDebug
```

产物在 `app/build/outputs/apk/{release,debug}/`。AAR 需要先在 `go-bridge` 里 gomobile bind，
命令见 README 的「构建」一节。
