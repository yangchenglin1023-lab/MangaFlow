# 漫流 MangaFlow

**把手机里的任何图片文件夹变成一条丝滑长图**——上下滑动连续阅读。漫画、图包、CG 集合、壁纸包、游戏资源文件夹都能看；对 RPG Maker MV/MZ 的加密资源格式（`.rpgmvp` / `.png_` 等）原生支持自动解密。免 Root、完全免费、无广告、完全离线（无任何联网权限）。

**为什么叫"漫流"**：漫 = 漫画/图包等一切图片集合，流 = 无缝拼接、惯性滑动的连续阅读体验。打开文件夹里的任意一张图，整个文件夹就变成一条不断的长图，从那一路滑到底。

## 功能

| 功能 | 说明 |
|---|---|
| **通用图片阅读** | 漫画/图包/壁纸/CG 集合等任何图片文件夹；PNG / JPG / JPEG / JFIF / GIF / BMP / WEBP / HEIF 全支持 |
| **我的画册** | 打开 App 先看画册列表（最近使用在前）：显示名称/路径/图片数/上次看到第几张，点击从上次位置继续；长按重命名、删除、定位；浏览文件夹时打开图片自动收藏，默认名=文件夹名 |
| 自动解密 | `.rpgmvp`（MV 图片）/ `.png_`（MZ 图片）自动解密显示；自动在上级目录找 `System.json` 提取密钥，找不到时用 PNG 文件头特性无密钥还原（图片 100% 有效） |
| **底部快速定位条** | 常驻底部滑动条，几百张图直接拖动跳转，右侧实时显示「当前/总数」；拖到哪预加载到哪 |
| **横竖屏** | 旋转屏幕即时切换版式，浏览位置原位保留（文件浏览器和画廊都不重启、不丢位置） |
| **图片格式** | 加密：rpgmvp / png_ / jpg_ / gif_ / webp_ / bmp_ 等 MZ 变体；普通：PNG / JPG / JPEG / JFIF / GIF / BMP / WEBP / HEIC / HEIF |
| **垂直连续画廊** | 点任意图片进入，整个文件夹的图**上下无缝拼接成一条长图**，惯性滚动丝滑连看，无翻页等待 |
| **像素级懒加载** | 滚到哪预加载到哪（视口上方 2.5 屏、下方 3.5 屏），视口优先解码；快速滚动自动丢弃过时的解码任务 |
| **滚动稳定** | 拖动完全跟手；惯性飞行中【不夹取】滚动范围（解码导致总高度变化也不会顶住滑动），高度变化以补偿量平滑吸收，飞行结束才统一回弹；视口以内容坐标锚定，尺寸变化/系统回收后**原位恢复**（浏览位置实时保存）；几何模型经桌面单元测试：2000 次随机解码视口纹丝不动、3000 次混合事件从未传送回顶部 |
| **内存安全（防崩溃回首页）** | 位图缓存按**字节预算**管理（min(96MB, 应用堆 1/4)，超出按距当前页远近淘汰），解码分辨率随设备内存自适应；淘汰只丢引用**不 recycle**（消灭与渲染线程的竞态崩溃）；开启 largeHeap——多管齐下杜绝 OOM 崩溃导致"回到第一张" |
| **漫画式甩动惯性** | 快甩飞远、轻滑走近（甩动速度×2 放大，力度∝手速）；未加载的区域照常滑过（占位显示，滑到位自动补图），一路丝滑滑到落点 |
| **排序** | 文件名**自然排序**（数字按数值：img1, img7, img34, img108, img223 —— 34 正确排在 223 前）/ 修改时间新→旧 / 旧→新，浏览器与画廊顺序一致，选择自动记忆 |
| 文件夹计数 | 浏览时每个文件夹显示「N 张图 · M 个文件 · K 个子目录」 |
| 缩放 | 双指缩放（1~4 倍，宽度铺满为基准）、双击放大 2 倍、放大后拖动平移 |
| 滚动 | 惯性滑动 / 音量键上下滚动 |
| 音频 | `.rpgmvo` / `.rpgmvm` / `.ogg_` / `.m4a_` 解密播放（OGG Vorbis / AAC） |
| 导出 | 一键把当前图导出为 PNG 到 `Download/RPGMV导出/` |
| 无密钥也能看 | 图片解密不依赖 System.json |

## 安装（3 步）

1. 把 `dist/MangaFlow-v2.2.apk` 传到手机（微信/QQ/USB 均可）
2. 点开 APK 安装，系统提示"未知来源应用"时选择允许
3. 首次打开点「添加画册」浏览手机存储，授权"管理所有文件"后即可使用

支持 Android 5.0（API 21）及以上，无需 Root。

## 使用

1. 打开 App 进入「我的画册」→ 点「添加画册」浏览手机存储
2. 进入目标文件夹——**下载的漫画目录、图包文件夹**、或游戏资源目录（MV 的 `www/img/pictures`、MZ 的 `img/pictures`），点任意图片 → **自动加入画册**并进入连续阅读
3. 以后从画册一点即达，从上次看到的位置继续
4. 长按画册可重命名 / 删除 / 在浏览器中定位

## 免责与用途声明 / Disclaimer

本应用是一个**本地图片浏览工具**，仅读取用户设备上已有的文件，不含任何联网功能。

- 适用于：查看您自己拥有权利的素材、RPG Maker 模组制作、游戏本地化翻译对照、同人创作参考、个人备份管理等场景。
- RPG Maker MV/MZ 的资源文件格式是公开资料，本工具为独立原创实现，未使用任何官方或第三方的专有代码。
- 请尊重版权：请勿将本工具用于提取、传播您不拥有权利的商业素材。使用者需自行遵守所在地区的法律法规。

**MangaFlow (漫流)** — A free, open-source, **offline local image reader for Android**. Turn any folder of images (manga, picture packs, CG collections, game assets) into one seamlessly-stitched vertical strip with comic-style inertial scrolling, a fast-seek slider, resume-from-last-position albums, and natural filename sorting (img1 < img2 < img10). It natively supports the publicly-documented RPG Maker MV/MZ resource formats (`.rpgmvp` / `.png_` etc., auto-decrypted). Pure Java, zero dependencies, ~60KB APK, no ads, no network permission.

This app only reads files already on the user's device and requests no network permission. The RPG Maker MV/MZ resource format is publicly documented; this is an original, clean-room implementation with no proprietary code. Intended for viewing content you have the right to access (manga/artwork you own, modding, translation, fan works, personal backup). Please respect copyright and the laws of your jurisdiction.

## 构建方法（本机已配置好）

```bash
./build.sh
```

免 Gradle 工具链：`tools/` 下自带 JDK 17（清华 TUNA 镜像）+ Android build-tools 34 + platform-34（dl.google.com），
构建流程为 aapt2 compile → aapt2 link → javac → d8 → jar → zipalign → apksigner。
签名密钥：`tools/rpgmv.keystore`（storepass: `rpgmvviewer`）。

跑解密算法单元测试：

```bash
./tools/jdk-17.0.20.1+1/bin/javac.exe -encoding UTF-8 -d build/testcp \
  app/src/com/deepwork/rpgmvviewer/RpgmvCrypto.java tools/src/RpgmvCryptoTest.java
./tools/jdk-17.0.20.1+1/bin/java.exe -cp build/testcp RpgmvCryptoTest
```

## 技术说明

RPG Maker MV/MZ 加密格式：16 字节头（`RPGMV` / `RPGMZ` 开头）+ 原始数据，其中紧跟头部的 16 字节与
`data/System.json` 中 `encryptionKey`（16 字节）异或，其余数据不加密。PNG 文件前 16 字节是固定的
（`89 50 4E 47 0D 0A 1A 0A 00 00 00 0D 49 48 44 52`），因此无需密钥即可还原图片。

APK 仅 50KB，纯 Java 实现，零第三方依赖。
