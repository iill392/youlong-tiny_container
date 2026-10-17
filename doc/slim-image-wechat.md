# 二改版：微信按需安装（镜像瘦身）

## 背景

改版 12 把约 750MB 的微信（Linux ARM64 原生版）烧录进 `rootfs.tar.zst`，
导致 APK 从 995MB 涨到 1.25GB。APK 瘦身（按需下载）已做完，但镜像本身仍含微信。

## 目标

打出不含微信的 slim 镜像，微信改为用户首次点击快捷命令时按需安装。

## 镜像构建改动（需在打镜像的机器上操作）

1. 构建 rootfs 时**跳过** `/opt/wechat` 的烧录步骤
2. **保留** `/opt/wechat-doctor.sh` / `wechat-reinstall.sh`（已在镜像内，负责从腾讯官方下载安装）
3. 快捷命令组「微信」的「启动」命令改为先检测：
   ```bash
   if [ -x /opt/wechat/wechat ]; then
     /opt/wechat/wechat
   else
     /opt/wechat-reinstall.sh  # 首次自动下载安装
   fi
   ```
   （当前已有「重装升级」命令调用该脚本，只需把「启动」也接上检测逻辑）
4. 重新打包 `rootfs.tar.zst`，体积预计从 ~968MB 降至 ~220MB
5. 发布到下载地址（`Global.ROOTFS_DOWNLOAD_URL`，当前指向 GitHub Releases）

## App 侧（已完成）

- APK 瘦身：`startDownloadImport()` 支持下载任意 rootfs，slim 镜像可直接用
- 无需改 App 代码，换镜像 URL 即可

## 注意事项

- 微信 Linux 原生版为腾讯官方 ARM64 构建，从官方源下载，合规性同现状
- 不用微信的用户节省 ~750MB 下载流量和存储空间
- 微信可独立升级，不必等整个镜像重打
