﻿# GitHub Actions 自动构建签名设置

## 步骤

### 1. 设置 GitHub Secrets

在你的 GitHub 仓库中设置以下 Secrets：

**仓库路径**：`Settings → Secrets and variables → Actions → Secrets → New repository secret`

| Secret 名称 | 值 |
| --- | --- |
| `KEYSTORE_BASE64` | `keystore_base64.txt` 文件的内容 |
| `KEYSTORE_PASSWORD` | `swupdater123` |
| `KEY_ALIAS` | `swupdater` |
| `KEY_PASSWORD` | `swupdater123` |

### 2. 触发 Release 构建

#### 方式一：推送 Tag（推荐）

```bash
# 创建并推送 tag
git tag -a v2.8.0 -m "Release v2.8.0"
git push origin v2.8.0
```

#### 方式二：手动触发

1. 访问仓库 → Actions → 选择 "Release" workflow
2. 点击 "Run workflow"
3. 输入版本号（如 `2.8.0`）
4. 点击绿色 "Run workflow" 按钮

### 3. 查看构建结果

- 构建成功后，访问仓库 → Releases
- 会自动生成带 APK 附件的 Release

---

## 签名信息

- **Keystore 文件**：`release.jks`（Base64 编码在 `keystore_base64.txt`）
- **Keystore 密码**：`swupdater123`
- **Key 别名**：`swupdater`
- **Key 密码**：`swupdater123`

---

## 工作流说明

项目已有两个 GitHub Actions 工作流：

### `build.yml`（CI 构建）
- 触发条件：推送到 `main` 分支或 PR
- 构建 Debug APK
- 上传 Artifact 供测试

### `release.yml`（发布构建）
- 触发条件：推送 `v*` tag 或手动触发
- 构建 Release APK（带签名）
- 自动创建 GitHub Release
