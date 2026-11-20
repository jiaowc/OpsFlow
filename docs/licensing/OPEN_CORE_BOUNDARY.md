# OpsFlow Open Core 边界（第 1 步）

> 策略：**同仓加锁**。开源提供可跑通的 CI/CD 核心；增值能力用 License 功能码解锁。  
> 本文是产品边界约定，后续开发以本文为准；实现拦截见第 2 步。

## 1. 功能码（定死）

| 功能码 | 中文名 | 覆盖范围 | 状态 |
|---|---|---|---|
| `deploy_approval` | 上线审批 | 审批流程、上线任务、审批动作、发布前审批门禁 | **已有** |
| `audit` | 操作审计 | 「操作审计」菜单与查询 API；审计写入可保留（便于开通后追溯） | **待加锁** |
| `notify_integration` | 集成通知 | 「集成与通知」中的飞书等外部通道配置与推送；站内信默认开源 | **待加锁** |
| `prod_rollback` | 生产回滚 | 对生产环境（`envType=prod`）的镜像回滚；非生产回滚开源 | **已加锁** |
| `branding` | 企业品牌 | 登录页 / 控制台自定义企业 Logo | **已加锁** |

签发 License 时 `features` 为上述字符串数组（小写、下划线）。  
未列出的码一律忽略，不授予额外能力。

### 打包建议

| 版本 | 功能码 |
|---|---|
| Community | （无商业码；仅开源能力） |
| Pro | `deploy_approval` + `audit` + `notify_integration` + `prod_rollback` + `branding` |
| Enterprise | Pro + 预留（如 `sso`，本期不实现） |

## 1.1 订阅模式（已定）

商业版采用 **按期订阅**，不卖永久解锁。

### 售卖形态

| 计划 | 周期 | 内容 |
|---|---|---|
| Pro 年订 / 月订 | 签发时写入 `expiresAt` | 上述 Pro 功能码全集 |
| Enterprise | 同上 + 合同支持 | Pro + 预留能力 |

### 技术形态（与现有 License 对齐）

- **载体**：离线签名 License 文件（已有 `payload + signature`），适合私有化/内网。
- **有效期**：必须填写 `expiresAt`；到期后商业功能关闭，Community 核心仍可用。
- **续费**：客户付款后，签发端生成**新** License（新 `licenseId` 或同客户滚动 `expiresAt`），客户在系统「License」页重新导入即可。
- **永不过期**：商业订阅 **禁止** `expiresAt` 为空；空仅允许内部/开发测试签发。
- **宽限期（建议第 2 步实现）**：过期后仍可用 **7 天** 商业功能，仅告警；满宽限期再硬拦截。
- **不强制在线心跳（首期）**：首期不做「定期联网校验」；内网客户靠本地过期时间。后续若要防长期盗用，再加可选在线激活。

### License 载荷字段约定

| 字段 | 订阅含义 |
|---|---|
| `customer` | 订阅客户名 |
| `licenseId` | 本张订阅凭证 ID（续费可新开一张） |
| `features` | 本订阅周期开通的功能码 |
| `issuedAt` | 签发时间 |
| `expiresAt` | **订阅到期时间（必填）** |
| `plan`（可选，第 2 步可加） | 如 `pro` / `enterprise`，便于展示 |

### 产品文案原则

- Community：永久免费，无订阅。
- Pro：订阅期内可用商业模块；到期回退到 Community 能力，数据与流水线任务保留。
- 续费不换库、不重装，只换 License 文件。

## 2. Community / Pro 对照

| 能力 | Community（开源） | Pro（商业） |
|---|---|---|
| 项目管理 / 服务 / 环境 / 集群 / 节点 | ✅ | ✅ |
| 凭据与组件（Git / Harbor / K8s 等基础接入） | ✅ | ✅ |
| 流水线 Job：创建、构建、部署、日志、阶段视图 | ✅ | ✅ |
| 非生产环境回滚 | ✅ | ✅ |
| 基础本地账号 + RBAC | ✅ | ✅ |
| 工作台基础统计 | ✅ | ✅ |
| 站内信（系统内通知） | ✅ | ✅ |
| 上线任务 + 审批流程 | ❌ | ✅ `deploy_approval` |
| 操作审计查询 | ❌ | ✅ `audit` |
| 飞书等外部通知集成 | ❌ | ✅ `notify_integration` |
| 生产环境回滚 | ❌ | ✅ `prod_rollback` |
| 企业 Logo 定制（登录页 / 控制台） | ❌ | ✅ `branding` |
| SSO / LDAP / OIDC | ❌（预留） | Enterprise 预留 |
| 多集群配额 / 生产保护策略包 | ❌（预留） | Enterprise 预留 |

## 3. 拦截约定（给第 2 步用）

1. **后端**：商业入口统一 `licenseService.requireFeature(...)`；禁止只藏菜单不拦 API。  
2. **前端**：菜单/区块用 `data-feature`；按钮用 `hasFeature`；无权限时隐藏或置灰并提示开通。  
3. **强校验**：对外交付默认 `opsflow.license.enforcement-enabled=true`；本地开发可临时关闭。  
4. **关闭强校验时**：视为全部功能开放（与现网行为一致），仅用于开发联调。  
5. **审计写入**：Community 可继续落库；**查询与导出**必须 `audit`。避免开通后历史空白。  
6. **通知**：无 `notify_integration` 时，上线任务仅允许站内信；飞书配置页与推送不可用。  
7. **回滚**：有 `pipeline:rollback` 权限仍不够；生产环境还必须有 `prod_rollback`。

## 4. 明确不锁（保持开源吸引力）

- 单机/私有化部署与基础配置  
- 端到端：拉代码 → 构建镜像 → 推仓库 → 部署到非生产  
- Git Webhook 自动构建的基础能力（高级策略以后再议）  
- 流水线模板/步骤定义的基础编辑  

## 5. 第 2 步进度

已完成：

1. 审计查询 API、飞书配置写入/推送、生产回滚入口已加 `requireFeature`。  
2. 前端菜单 `data-feature` + License 页展示计划/到期/余量/宽限/各功能开关。  
3. 过期宽限期 7 天；宽限后回退 Community。  
4. 强校验关闭时仍全部开放（开发默认）。

待办（对外售卖前）：

1. 签发端强制商业 License 带 `expiresAt` + 多功能码。  
2. 将 `opsflow.license.enforcement-enabled` 设为 `true` 做无钥/有钥/过期验收。  
3. （可选）到期前 14 天 UI 提醒。
