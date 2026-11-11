# CI 与自动分发

## 触发

| 事件 | 行为 |
|---|---|
| push 到 `main` | 构建 debug APK，产物推到 `apk-latest` 分支，状态写进 `ci-status` 分支 |
| push tag `v*` | 同上，另外挂一个 GitHub Release |
| 手动 Run workflow | 同上（不挂 Release） |

## 为什么用分支投递而不是 Release

这台手机 HTTPS 出不去，Release 页面下载不了；但 `ssh.github.com:443` 是通的。
所以 CI 用 `commit-tree` 直接构造一个单提交，force-push 到 `apk-latest`，
分支上永远是：

```
airannounce-debug.apk
apk.sha256
build-info.txt     # commit / version_name / version_code / built_at / apk_sha256
```

历史不增长（每次覆盖同一条）。

## 手机端取包

```sh
cd /workspace/android-build/airannounce
git fetch origin apk-latest
git show origin/apk-latest:build-info.txt
git show origin/apk-latest:airannounce-debug.apk > /data/local/tmp/aa.apk
```

然后（Android 侧）：

```sh
chmod 644 /data/local/tmp/aa.apk && pm install -r /data/local/tmp/aa.apk
```

## 查 CI 是否成功

```sh
git fetch origin ci-status
git show origin/ci-status:ci-status.txt
```

## 签名（已解决，2026-11-11 验证通过）

做法与踩过的坑：

1. runner 上的默认 debug 密钥不可信 —— 把 keystore 放到 `$HOME/.android/debug.keystore`
   之后，AGP 仍然用了另一个临时密钥（每轮产物证书指纹都不同）。
2. 因此改为**显式 signingConfig**：`app/build.gradle.kts` 读环境变量
   `AIRANNOUNCE_DEBUG_KEYSTORE`，有值就用它签 debug 包；本地构建不设该变量，行为不变。
3. keystore 来自仓库 secret。这个仓库里它实际叫 `XWWP`（建的时候自动填充成用户名），
   工作流同时接受 `DEBUG_KEYSTORE_B64` 和 `XWWP` 两个名字。
4. 构建完成后把证书指纹写进 `ci-status`，手机端不用看 Actions 日志就能判断签名对不对。

实测结果：

```
keystore_source=secret:XWWP
signer_sha256=0e3413715d6e16863cb83ba51333532f140914cf7ebc90c7496251f6cc1c0047
```

与本机包完全一致，且直接把 CI 产物 `pm install -r` 装进真机成功（2.7.12）。

### 历史对比（未修好前）

```
CI 包     cb6171de... / a34a594a... / 1c0b9400...  每轮都不同 → 临时密钥
本机包    0e341371...
```

## 签名（旧说明，保留作参考）

AGP 的默认 debug 签名读 `$HOME/.android/debug.keystore`。CI runner 是干净的，
不提供密钥时会临时生成一个，**签名与本地包不同**，`pm install -r` 会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

实测对比（2026-11-11）：

```
CI 包     certificate SHA-256: cb6171de3554d08fafac422a07c20f894b951305d7d7aa3323cf25d8535223e4
本机包    certificate SHA-256: 0e3413715d6e16863cb83ba51333532f140914cf7ebc90c7496251f6cc1c0047
```

两种补法，任选其一：

1. **GitHub secret（推荐）**：把本机 `~/.android/debug.keystore` 做 base64，
   存到仓库 secret `DEBUG_KEYSTORE_B64`。工作流会自动还原到 `$HOME/.android/debug.keystore`。
   现成的 base64 已导出到 `/storage/emulated/0/Download/ci-debug-keystore.b64`。
2. **仓库内密钥**：把 keystore 放成 `ci/debug.keystore` 提交。省事，但密钥公开，
   任何人都能签一个本机可接受的"更新包"。除非仓库是私有的，否则不建议。

## 已知限制

- CI 构建用 Maven 版 aapt2（x86_64 runner 原生执行），不需要本机那套 QEMU 桥接；
  `gradle.properties` 里没有提交 `aapt2FromMavenOverride`，两边互不影响。
- `*.apk` 在 `.gitignore` 里，推送走 `commit-tree` 绕开索引，所以工作区不会被产物污染。
