# 换电脑继续开发 Po0 分支

可以。应用源码、Gradle 配置和子模块版本由 Git 保存；换电脑后还需安装构建工具、准备原生依赖。覆盖升级现有 Po0 应用时，需要另行安全转移原签名密钥。

## 1. 克隆正确分支

```powershell
git clone --branch codex/po0-whitelist --recurse-submodules https://github.com/ErWenF/v2rayNG.git
Set-Location v2rayNG
```

默认 `master` 分支目前没有 Po0 功能，请明确选择 `codex/po0-whitelist`。已有克隆切换到该分支后，执行 `git submodule update --init --recursive`。

## 2. 安装工具并准备原生依赖

1. 安装 Git、PowerShell（`pwsh`）和 Android Studio。按 `.github/workflows/build.yml` 的 `java-version` 安装 JDK；Android Studio 打开仓库内的 `V2rayNG` 文件夹，Gradle JDK 选择该版本。
2. 在 SDK Manager 安装 `V2rayNG/app/build.gradle.kts` 中 `compileSdk` 对应的 Android SDK Platform，以及 `.github/workflows/build.yml` 的 `packages` 所列 Build-Tools 和 Platform-Tools。依赖版本另见 `V2rayNG/gradle/libs.versions.toml`，Gradle 使用仓库 Wrapper。SDK 路径由 Android Studio 写入本机 `V2rayNG/local.properties`；该文件不提交。
3. 在仓库根目录执行：

```powershell
pwsh -File ./scripts/Prepare-Po0Native.ps1
```

脚本从 [2.3.11.1 发布页](https://github.com/ErWenF/v2rayNG/releases/tag/2.3.11.1) 下载 `v2rayNG-Po0-native-inputs-2.3.11.1.zip`，验证仓库内 `scripts/po0-native-inputs.json` 固定的 SHA-256，再将 `libv2ray.aar` 与各 ABI 的 HEV 库放入被 Git 忽略的 `V2rayNG/app/libs`。附件须已上传；下载或校验失败时先解决原因。Windows 和其他安装 `pwsh` 的环境可用此方式准备应用层开发依赖。

这些原生库对应本分支锁定的源码版本。修改 Kotlin、Compose、资源等应用层代码时可以复用；不要随意替换成其他版本的 AAR 或 `.so`。修改 `AndroidLibXrayLite`、`hev-socks5-tunnel` 或升级其 gitlink 时，需要按 `.github/workflows/build.yml`、`compile-hevtun.sh` 及子模块说明，从对应源码重新编译并验证所有声明的 ABI；预编译包不包含尚未重新编译的原生改动。

## 3. 测试和开发构建

从 `V2rayNG` 目录执行：

```powershell
Set-Location V2rayNG
.\gradlew.bat :app:testPlaystoreDebugUnitTest :app:compilePlaystoreDebugKotlin :app:assemblePlaystoreDebug
```

macOS／Linux 使用 `./gradlew` 替换 `.\gradlew.bat`。APK 位于 `app/build/outputs/apk/playstore/debug/`。涉及发行渠道差异时，还需运行 `:app:testFdroidDebugUnitTest`、`:app:compileFdroidDebugKotlin` 和 `:app:assembleFdroidDebug`；API 兼容性等静态检查按仓库 `AGENTS.md` 要求运行 lint。

新电脑的 Debug 构建通常使用本机新生成的签名，无法覆盖已安装且使用原 Po0 证书的 APK；开发测试可以使用模拟器或独立测试设备。

## 4. 保持正式版本的签名和升级连续性

仓库不包含 `po0-signing.jks`、密码或私钥。需要覆盖升级原 Po0 应用时，将已有密钥通过安全方式转移到新电脑的仓库外目录，并单独保存密码和原别名。新建同名文件、同名证书或同名别名，都不能代替原私钥。

原 Po0 证书的 SHA-256 指纹为 `7ce6409774f34f0aa1b8f31212d970c0a7fbacb77dc11d8f76c46e466ac4e90c`，证书主题名为 `Android Debug`。Release 构建类型和证书名称是不同信息，该证书不是上游官方证书。可用 `keytool -list -v -keystore <密钥文件路径>` 核对证书，密码按提示输入。

在 `V2rayNG` 目录，将以下环境变量通过安全输入或 CI secret 注入后执行；示例不包含实际密码：

```powershell
# PO0_KEYSTORE_FILE：原 po0-signing.jks 的绝对路径
# PO0_KEY_ALIAS：原别名
# PO0_STORE_PASSWORD、PO0_KEY_PASSWORD：原密码
.\gradlew.bat :app:assemblePlaystoreRelease `
  "-Pandroid.injected.signing.store.file=$env:PO0_KEYSTORE_FILE" `
  "-Pandroid.injected.signing.store.password=$env:PO0_STORE_PASSWORD" `
  "-Pandroid.injected.signing.key.alias=$env:PO0_KEY_ALIAS" `
  "-Pandroid.injected.signing.key.password=$env:PO0_KEY_PASSWORD"
```

Release APK 位于 `app/build/outputs/apk/playstore/release/`。发布前用 Android SDK 的 `apksigner verify --print-certs <APK路径>` 核对签名，并核对包名、版本和文件哈希。不要将密钥、密码或含密码的本机配置提交到 Git。

当前 `versionName`／tag 为 `2.3.11.2`，Gradle 基础 `versionCode` 为 `753`，Playstore APK 实际输出为 `4000753`。后续版本递增基础 `versionCode`，沿用同一包名和证书，并使用递增的四段数字 `versionName`／tag（例如 `2.3.11.3`）；现有更新比较器不支持 `-po0` 等文字后缀。应用内“检查更新”使用本 fork 的 Releases，按设备架构选择 APK 并打开下载，下载完成后由 Android 安装器确认升级；不会静默安装。已安装的 Po0 `2.3.11.1` 也使用此更新源。

## 5. 验证范围

源码克隆、原生依赖恢复、编译和签名检查解决的是继续开发与打包条件。**Not run**：另一台电脑从零克隆并构建（尚无第二台环境），以及设备覆盖升级与备份恢复、真实 Po0 Token 联调、网络切换、Doze 与耗电测量（尚无相应设备测试）。运行行为的验收项目见 [Po0 白名单使用与验收说明](PO0-WHITELIST.md)。
