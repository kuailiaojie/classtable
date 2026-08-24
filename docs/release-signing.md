# Release 签名配置

本文档说明如何为 Release APK 配置正式签名。

## 签名材料

| 项 | 值 |
|---|---|
| 密钥库文件 | 本地 keystore 文件(PKCS12 格式) |
| 密钥库类型 | PKCS12 |
| 别名(alias) | `key0` |
| 密钥库密码 / 密钥密码 | 见本地私密记录,严禁提交到仓库 |

> 密钥库与密码为机密信息:密钥库文件及其 Base64 编码不得提交到 Git 仓库,密码不得写入任何入库文件。

## GitHub Actions 签名(CI)

在仓库 **Settings → Secrets and variables → Actions** 中配置以下 4 个密钥:

| Secret | 值 | 说明 |
|---|---|---|
| `KEYSTORE_BASE64` | 密钥库文件的 Base64 编码 | 由本地 keystore 生成,见下文 |
| `KEYSTORE_PASSWORD` | 密钥库密码 | 与本地一致 |
| `KEY_ALIAS` | `key0` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 | 与本地一致 |

配置完成后,`Build APK` 工作流构建的 release APK 将自动签名;未配置时产出未签名 APK。

### 生成 KEYSTORE_BASE64

```powershell
# Windows
[System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes('C:\path\to\keystore')) | Set-Content keystore.b64
```

```bash
# Linux / macOS
base64 -w0 /path/to/keystore > keystore.b64
```

将 `keystore.b64` 的**完整内容**(单行)填入 `KEYSTORE_BASE64`,使用后删除该文件。

## 本地签名构建

在仓库根目录设置环境变量后执行构建(Windows PowerShell 示例):

```powershell
$env:KEYSTORE_FILE = 'C:\path\to\keystore'
$env:KEYSTORE_PASSWORD = '<密码>'
$env:KEY_ALIAS = 'key0'
$env:KEY_PASSWORD = '<密码>'
.\gradlew.bat assembleRelease
```

产物位于 `app/build/outputs/apk/release/`,文件名带 `-release` 即为已签名 APK(可用 `apksigner verify` 验证)。

## 验证签名

```powershell
# Android SDK build-tools 自带 apksigner
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```
