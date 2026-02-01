# StartFRP - Android FRP客户端

<div align="center">
  <h3>一个功能强大的Android FRP客户端应用</h3>
  <p>支持开机自启动、后台保活、Shizuku权限管理等高级特性</p>
  <br>
  <a href="#功能特性">功能特性</a> •
  <a href="#系统要求">系统要求</a> •
  <a href="#安装方法">安装方法</a> •
  <a href="#使用说明">使用说明</a> •
  <a href="#配置文件说明">配置文件</a> •
  <a href="#常见问题">常见问题</a> •
  <a href="#技术原理">技术原理</a>
  <br><br>
</div>


## 功能特性

### 核心功能
- ✅ FRP服务的启动和停止控制
- ✅ 实时显示FRP运行状态
- ✅ 详细的日志输出和复制功能
- ✅ 配置文件管理和编辑
- ✅ 一键式操作界面

### 高级特性
- ✅ **开机自启动**：系统启动后自动运行FRP服务
- ✅ **无障碍服务增强保活**：利用无障碍服务权限保持应用活跃
- ✅ **Shizuku权限支持**：使用Shizuku执行FRP，获得更高权限
- ✅ **守护进程**：监控并自动重启FRP进程
- ✅ **电池优化豁免**：引导用户添加到电池优化白名单
- ✅ **不在最近任务中显示**：减少被系统清理的风险

### 界面特性
- ✅ 紧凑美观的用户界面
- ✅ 实时状态反馈和视觉提示
- ✅ 详细的日志查看和管理
- ✅ 响应式布局，适配不同屏幕尺寸

## 系统要求

- **Android版本**：6.0+（API 23+）
- **架构支持**：ARM64架构设备
- **存储空间**：至少100MB可用空间
- **可选依赖**：
  - Shizuku应用（如需使用Shizuku功能）
  - 无障碍服务权限（如需使用增强保活功能）

## 安装方法

### 方法一：直接安装APK
1. 下载最新的APK文件到设备
2. 在设备上允许安装来自未知来源的应用
3. 点击APK文件进行安装
4. 首次启动时，应用会自动复制必要的FRP文件

### 方法二：从源码构建
1. **克隆项目**：`git clone https://github.com/usertest001/startfrp.git`
2. **打开项目**：使用Android Studio打开项目
3. **同步依赖**：等待Gradle依赖同步完成
4. **构建APK**：点击"Build" > "Build Bundle(s) / APK(s)" > "Build APK(s)"
5. **安装测试**：将生成的APK安装到设备进行测试

## 使用说明

### 首次使用
1. 启动应用后，点击"配置"按钮编辑FRP配置文件
2. 配置文件格式为TOML格式，参考FRP官方文档
3. 保存配置后，点击"启动FRP"按钮启动服务
4. 查看日志输出，确认FRP服务是否正常运行

### 开机自启动设置
1. 勾选主界面中的"自启动"选项
2. 按照弹出的提示在系统设置中开启应用的自启动权限
3. 重启设备后，FRP服务会自动启动

### Shizuku模式
1. 确保已安装并启动Shizuku应用
2. 在主界面勾选"激活Shizuku"选项
3. 按照提示授予应用Shizuku权限
4. 点击"安装Shizuku"按钮复制必要文件
5. 启动FRP服务，此时会使用Shizuku权限运行

### 保活设置
1. 勾选主界面中的"无障碍"选项
2. 按照提示在系统设置中开启无障碍服务
3. 点击"电池优化"按钮，将应用添加到电池优化白名单
4. 勾选"不显示"选项，减少被系统清理的风险
5. 点击"启动守护进程"按钮，确保FRP进程稳定运行

### 守护进程
1. 点击主界面中的"启动守护进程"按钮
2. 守护进程会定期检查FRP进程状态
3. 当FRP进程异常退出时，守护进程会自动重启它
4. 守护进程运行时会显示持续通知

## 配置文件说明

### 配置文件位置
配置文件位于应用数据目录下的`frp/frpc.toml`，可通过"配置"按钮编辑。

### 示例配置

```toml
# FRP客户端配置
[common]
server_addr = "frp.example.com"  # FRP服务器地址
server_port = 7000               # FRP服务器端口
token = "your_token"             # 连接密钥

# 示例：TCP端口转发（SSH）
[[proxies]]
name = "ssh"                     # 代理名称
type = "tcp"                     # 代理类型
local_ip = "127.0.0.1"           # 本地IP
local_port = 22                  # 本地端口
remote_port = 6000               # 远程端口

# 示例：HTTP域名转发（Web服务）
[[proxies]]
name = "web"                     # 代理名称
type = "http"                    # 代理类型
local_ip = "127.0.0.1"           # 本地IP
local_port = 80                  # 本地端口
custom_domains = ["frp.example.com"]  # 自定义域名
```

### 配置提示
- 配置文件采用TOML格式，语法简洁明了
- 可添加多个代理配置，每个代理使用`[[proxies]]`标记
- 详细配置选项请参考FRP官方文档

## 常见问题

### Q: FRP服务启动失败怎么办？
**A:** 查看应用内日志输出，检查以下几点：
- 配置文件是否正确（服务器地址、端口、token等）
- FRP服务器是否可访问
- 网络连接是否正常
- 必要的文件是否存在

### Q: 开机自启动不生效？
**A:** 请检查以下设置：
1. 是否已在系统设置中开启应用自启动权限
2. 是否已将应用添加到电池优化白名单
3. 系统是否限制了后台应用活动
4. 无障碍服务是否已开启

### Q: Shizuku模式无法使用？
**A:** 请检查：
1. Shizuku应用是否已安装并正常运行
2. 是否已授予应用Shizuku权限
3. 点击"安装Shizuku"按钮是否成功复制文件
4. Shizuku服务是否可用（可在Shizuku应用中查看）

### Q: 应用容易被系统清理？
**A:** 建议启用以下所有保活设置：
1. 开启无障碍服务增强保活
2. 添加应用到电池优化白名单
3. 启用"不在最近任务中显示"选项
4. 启动守护进程
5. 保持前台服务通知显示

## 技术原理

### FRP运行机制
- **核心实现**：使用预编译的`libfrpc.so`原生库运行FRP
- **运行模式**：支持两种运行模式
  - 直接运行：通过普通方式执行FRP
  - Shizuku模式：使用Shizuku执行FRP，获得更高权限
- **进程管理**：通过进程监控确保FRP稳定运行

### 保活机制
- **前台服务**：使用前台服务显示持续通知，避免被系统回收
- **无障碍服务**：利用无障碍服务的特殊权限保持应用活跃
- **守护进程**：独立的守护服务监控并重启FRP进程
- **电池优化豁免**：减少系统对应用的资源限制

### 权限管理
- **运行时权限**：无需特殊权限即可基本使用
- **可选权限**：
  - 无障碍服务权限（用于增强保活）
  - Shizuku权限（用于高级功能和更高权限操作）

### 开机自启动
- **实现方式**：使用`BootReceiver`广播接收器监听系统启动完成事件
- **启动流程**：系统启动 → 触发广播 → 启动FRP服务 → 启动前台保活服务
- **可靠性**：多重保活机制确保服务稳定运行

## 🛠️ 开发者信息

### 项目结构

```
startfrp/
├── app/
│   ├── src/main/java/pub/log/startfrp/
│   │   ├── MainActivity.java          # 主界面和用户交互
│   │   ├── FrpcService.java           # FRP服务管理
│   │   ├── StatusService.java         # 前台保活服务
│   │   ├── AccessibilityKeepAliveService.java  # 无障碍保活服务
│   │   ├── FrpcDaemonService.java     # 守护进程服务
│   │   ├── BootReceiver.java          # 开机自启动接收器
│   │   └── ...
│   ├── src/main/res/                  # 资源文件
│   └── build.gradle.kts               # 构建配置
├── gradle/                            # Gradle配置
└── README.md                          # 项目说明
```

### 构建要求
- **开发工具**：Android Studio
- **SDK版本**：Android SDK 23+
- **NDK版本**：支持ARM64架构编译
- **构建系统**：Gradle

### 自定义构建
1. **更换FRP内核**：替换`app/src/main/jniLibs/arm64-v8a/libfrpc.so`文件
2. **修改签名配置**：编辑`gradle.properties`中的签名信息
3. **调整构建选项**：修改`app/build.gradle.kts`中的构建配置

## 📄 许可证

本项目采用**MIT许可证**开源。

```
MIT License

Copyright (c) 2026 StartFRP

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 🙏 致谢

- [FRP](https://github.com/fatedier/frp) - 优秀的反向代理工具，本项目的核心依赖
- [Shizuku](https://github.com/RikkaApps/Shizuku) - 提供高级权限管理，实现更强大的功能
- [frp-Android](https://github.com/AceDroidX/frp-Android) - 参考了该项目的实现思路和技术方案
- [AndroidX](https://developer.android.com/jetpack/androidx) - 提供现代化的Android组件库

## 📝 更新日志

### v1.0.260201 (APK版本)
- ✨ **版本更新**：更新到v1.0.260201版本
- ✨ **界面颜色调整**：统一了按钮颜色，优化了视觉效果
- ✨ **移除了未使用的代码**：清理了比亚迪车辆相关的无用代码，无byd系统签名，无法保证锁车后台运行，后续找方法实现
- ✨ **优化了沉浸式状态栏实现**：确保状态栏正常显示

### v1.0.260125 (APK版本)
- ✨ **正式发布第一个版本**：完整的FRP客户端功能
- ✨ **全新的用户界面设计**：现代化、简洁美观的界面
- ✨ **优化的开机自启动逻辑**：更可靠的启动机制
- ✨ **增强的Shizuku权限管理**：更好的权限处理和错误提示
- ✨ **改进的保活机制**：多重保活策略，提高服务稳定性
- ✨ **守护进程功能**：自动监控和重启FRP进程
- ✨ **电池优化豁免引导**：更清晰的用户引导流程
- ✨ **详细的日志查看和复制功能**：方便排查问题
- ✨ **修复了多个稳定性问题**：解决了部分设备上的崩溃和卡顿问题
- ✨ **提高了应用的兼容性**：适配更多设备和Android版本

### 内核版本
- **FRP内核版本**：0.66.0

## 📞 联系方式

- **项目地址**：[https://github.com/usertest001/startfrp](https://github.com/usertest001/startfrp)
- **问题反馈**：请在GitHub Issues中提交问题
- **开发者**： [yyx](https://github.com/usertest001/)

---

<div align="center">
  <p>🌟 如果您觉得本项目有用，请给个Star支持一下！</p>
  <p>📢 欢迎提交Pull Request，共同改进项目！</p>
</div>
