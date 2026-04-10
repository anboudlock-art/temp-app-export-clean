# BLE Password Lock - 蓝牙锁密码修改工具

## 📱 应用简介

这是一个用于修改蓝牙锁密码的Android应用，支持SYD8811芯片的蓝牙锁设备。

### 核心功能
- ✅ 扫描和连接蓝牙设备
- ✅ 验证设备密码
- ✅ 修改设备密码
- ✅ 支持Nordic UART协议
- ✅ ASCII编码
- ✅ 累加+异或双重校验

## 🚀 快速开始

### GitHub Actions 自动构建

1. **上传代码到GitHub**
2. **启用GitHub Actions**
3. **等待自动编译**
4. **下载APK**

## 📋 系统要求

### 设备要求
- Android 5.0 (API 21) 或更高
- 支持BLE（低功耗蓝牙）
- 位置权限授予

### 固件要求
- 芯片：SYD8811
- 固件版本：SYD8811_FINAL_REAL_FIX.bin
- 默认密码：000000
- 设备名称：以 LOCK_ 开头

## 📱 使用说明

### 1. 授予权限
首次启动时，授予以下权限：
- 位置权限
- 蓝牙权限

### 2. 扫描设备
- 确保蓝牙已开启
- 点击"扫描设备"按钮
- 等待发现设备

### 3. 连接设备
- 从列表中选择设备
- 等待连接成功

### 4. 修改密码
- 输入新的6位密码
- 点击"修改密码"按钮
- 等待60秒完成修改

## 🎯 技术规格

### 蓝牙规格
- **协议**: Nordic UART Service
- **服务UUID**: 6E400007-B5A3-F393-E0A9-E50E24DCCA9E
- **TX特征UUID**: 6E400008-B5A3-F393-E0A9-E50E24DCCA9E
- **RX特征UUID**: 6E400009-B5A3-F393-E0A9-E50E24DCCA9E

## 📦 应用信息

- **应用ID**: com.example.blepassword
- **版本**: 1.0 (versionCode: 1)
- **最小SDK**: Android 5.0 (API 21)
- **目标SDK**: Android 13 (API 33)

## 🔒 权限说明

- `BLUETOOTH` - 蓝牙基础功能
- `BLUETOOTH_ADMIN` - 蓝牙管理
- `BLUETOOTH_SCAN` - 蓝牙扫描
- `BLUETOOTH_CONNECT` - 蓝牙连接
- `ACCESS_FINE_LOCATION` - 精确位置

## ✅ 版本信息

**v12 FINAL (2025)**
- 修复所有编译错误
- 改进Android版本兼容性
- 修正蓝牙API调用
- 优化错误处理
