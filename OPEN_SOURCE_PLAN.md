# 开源上传 GitHub 计划（漫流 MangaFlow）

> 定位原则：**本仓库是一个通用本地图片阅读器**（漫画 / 图包 / 图片文件夹的连续阅读工具），
> 附加支持 RPG Maker MV/MZ 公开文档化的资源格式。完全离线、无联网权限、原创实现。
> 主用途是"看图阅读"，这一定位在合规上也更有利。所有环节按此定位执行。

## 一、合规自查清单（上传前逐项确认）

| # | 检查项 | 状态 | 说明 |
|---|---|---|---|
| 1 | 代码 100% 原创，无第三方专有代码 | ✅ | 全部为本项目原创实现（含解密算法，基于公开文档化的文件格式），未复制任何现有项目代码 |
| 2 | LICENSE（MIT）已添加 | ✅ | 见 `LICENSE` |
| 3 | **签名密钥不入库** | ✅ | `tools/rpgmv.keystore` 已加入 `.gitignore`（密钥泄露可被用来伪造同签名应用） |
| 4 | 体积巨大的工具链不入库 | ✅ | `tools/jdk-*`、`tools/sdk`、`tools/downloads` 已忽略，README 说明如何按需下载 |
| 5 | 仓库不含任何游戏素材/受版权保护资源 | ✅ | 图标为程序生成的原创图形；示例不携带真实游戏文件 |
| 6 | README 含免责与用途声明 | ✅ | 中英双语，明确"本地查看器、请尊重版权" |
| 7 | 不包含个人信息 | 待查 | 提交前检查代码注释/文档中无邮箱、手机号等（提交邮箱建议用 GitHub noreply） |
| 8 | 不宣传"破解/盗版"用途 | ✅ | 全部文案以"查看/浏览/管理"表述，不出现引导盗版的措辞 |

## 二、上传步骤

### 第 1 步：本地初始化仓库
```bash
cd D:/DeepWork/RpgmvpRead
git init
git add -A          # .gitignore 已排除 build/dist/tools 大目录
git status          # ★ 人工核对：确认没有 keystore、apk、jdk 混入
git commit -m "漫流 MangaFlow v2.2：通用本地图片连续阅读器，支持 RPG Maker MV/MZ 资源格式"
```

### 第 2 步：创建 GitHub 仓库（先私有再公开）
1. GitHub → New repository → 名称建议 `MangaFlow`
2. 可见性先选 **Private**（先自查一遍再转公开）
3. 关联并推送：
```bash
git remote add origin https://github.com/<你的用户名>/MangaFlow.git
git branch -M main
git push -u origin main
```

### 第 3 步：转公开前的最后检查
- 网页端浏览每个文件，确认无敏感内容
- 确认 README 渲染正常（表格、双语、免责声明）
- Settings → General → Danger Zone → Change visibility → **Public**

### 第 4 步：发布 Release（附 APK）
1. 打 tag：`git tag -a v2.0 -m "首个公开版本" && git push --tags`
2. GitHub → Releases → Draft a new release → 选 tag `v2.2`
3. 上传 `dist/MangaFlow-v2.2.apk` 作为附件
4. Release 说明写：功能列表、安装 3 步、SHA256（`sha256sum` 输出）、"仅本地查看、无联网权限"
5. 仓库页面 → About → 添加 Topics：`android` `image-viewer` `rpg-maker` `rpg-maker-mv` `rpg-maker-mz` `offline` `no-ads`

### 第 5 步：发布后维护
- Issues 用于收集机型适配问题（我们自己只有一台设备验证过）
- 后续版本：改代码 → `./build.sh` → 跑四套测试 → commit → 新 tag → 新 Release

## 三、风险与边界说明

1. **工具性质**：读取本地文件的查看器，与 GitHub 上长期存在的同类解密/查看工具（如 Petschko's RPG-Maker-MV-Decrypter 等）同一类别，符合平台规则。
2. **不越线的行为**：不提供任何游戏本体/素材下载；不做"一键提取整包并打包分享"功能；文案不引导盗版。
3. **用户责任边界**：README 已声明使用者需遵守当地法律、尊重版权——这是此类工具的标准且被广泛接受的做法。
4. **若收到侵权投诉（DMCA）**：GitHub 会先行下架再申诉；保持仓库干净（无任何受版权保护素材）即无实际风险点。

## 四、可选增强（发布后按需）
- README 英文完整版（目前为中英混合摘要）
- GitHub Actions：Ubuntu 上用同样的手工工具链做 CI 构建校验
- 手机截图（4~6 张：画册页/浏览器/阅读页/滑动条）放入 `docs/screenshots/`
- Issue 模板（机型/安卓版本/复现步骤）
