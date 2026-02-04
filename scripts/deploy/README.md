# 部署脚本目录

## 脚本说明

- **start.sh**: 启动应用脚本
- **stop.sh**: 停止应用脚本
- **restart.sh**: 重启应用脚本（停止 -> 编译 -> 启动）

## 使用方法

```bash
# 启动应用
./scripts/deploy/start.sh

# 停止应用
./scripts/deploy/stop.sh

# 重启应用
./scripts/deploy/restart.sh
```

## 配置说明

脚本会自动检测并使用外部配置文件（`config/application.yml`），如果不存在则使用项目内默认配置。

## 日志位置

应用日志默认位置：`logs/app.log`

查看日志：
```bash
tail -f logs/app.log
```
