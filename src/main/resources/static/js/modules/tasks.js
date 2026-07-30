// 上线任务模块：列表表格、创建、筛选、详情与审批操作入口（业务细节见后端 CD/审批服务）

//
// 职责概览：
// - 上线任务列表表格：加载、筛选、按创建时间倒序渲染
// - 任务创建：服务 / Harbor 项目 / 版本三级联动，拼接完整镜像地址并提交
// - 审批后 CD：创建时绑定 CD 流水线模版，审批通过后由后端自动触发部署
// - 模块镜像展示：表格列展示完整镜像、服务编码与服务端口
// - 审批链渲染：按步骤展示审批人节点及通过/待审/拒绝状态
// - 业务进度：统一通过 resolveTaskProgress 映射为单一进度徽章
// - 筛选：按进度、集群、「待我审批」过滤任务
// - 任务详情 / 删除 / 审批操作入口
//
// 全局缓存：
// - window._taskListCache：最近一次加载的完整任务列表（筛选前）
// - window._taskCreateEnvsCache / _taskCreateHarborRegistry：创建表单上下文
// ============================================

window._taskListCache = [];

function formatNotifyChannels(channels) {
    if (!channels || !channels.length) {
        return '<span>通知: 站内信</span>';
    }
    const labels = channels.map(c => {
        if (c === 'inbox') return '站内信';
        if (c === 'feishu') return '飞书';
        return c;
    });
    return `<span>通知: ${labels.join(' + ')}</span>`;
}

function formatNotifyChannelsText(channels) {
    if (!channels || !channels.length) return '站内信';
    return channels.map(c => {
        if (c === 'inbox') return '站内信';
        if (c === 'feishu') return '飞书';
        return c;
    }).join(' + ');
}

function formatDeployModeText(mode, parallelism) {
    if (mode === 'parallel') {
        const n = parallelism && parallelism > 0 ? parallelism : 3;
        return `有限并行（并发 ${n}）`;
    }
    return '串行';
}

function escapeTaskHtml(text) {
    if (text == null) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function resolveTaskProgress(task) {
    const approval = (task.approvalStatus || '').toLowerCase();
    const deploy = (task.taskStatus || task.status || '').toLowerCase();
    const records = task.approvalRecords || [];

    // 终态：审批拒绝或任务取消
    if (approval === 'rejected' || deploy === 'cancelled') {
        if (approval === 'rejected') {
            return { key: 'rejected', label: '已拒绝', tone: 'danger' };
        }
        return { key: 'cancelled', label: '已取消', tone: 'muted' };
    }
    if (deploy === 'failed') {
        return { key: 'failed', label: '失败', tone: 'danger' };
    }
    if (deploy === 'success') {
        return { key: 'success', label: '已发布', tone: 'success' };
    }
    // 部署进行中（含 building 兼容）
    if (deploy === 'deploying' || deploy === 'building') {
        return { key: 'deploying', label: '发布中', tone: 'info' };
    }
    // 审批已通过或无需审批，等待/准备发布
    if (approval === 'approved' || approval === 'none') {
        if (deploy === 'pending' || !deploy) {
            return { key: 'ready_to_deploy', label: '待发布', tone: 'warn' };
        }
        return { key: 'ready_to_deploy', label: '待发布', tone: 'warn' };
    }
    // 审批进行中：已有通过记录则为「审批中」，否则「待审批」
    if (approval === 'pending') {
        const hasApproved = records.some(r => (r.status || '').toLowerCase() === 'approved');
        if (hasApproved) {
            return { key: 'approving', label: '审批中', tone: 'info' };
        }
        return { key: 'pending_approval', label: '待审批', tone: 'warn' };
    }
    return { key: deploy || 'pending', label: deploy || '等待中', tone: 'muted' };
}

function resolveApproverLabel(record) {
    if (!record) return '-';
    const name = (record.approverName || record.approver || '').trim();
    if (!name) return '未指定';
    if (name.indexOf('feishu:') === 0) return name.slice(7);
    return name;
}

function renderApprovalChainHtml(task) {
    const records = [...(task.approvalRecords || [])].sort((a, b) => (a.currentStep || 0) - (b.currentStep || 0));
    if (!records.length) {
        return `<span class="task-chain-empty">${escapeTaskHtml(task.approvalFlowName || '-')}</span>`;
    }
    return records.map(r => {
        const status = (r.status || '').toLowerCase();
        const label = escapeTaskHtml(resolveApproverLabel(r));
        // 按审批状态附加 CSS 类：已完成 / 当前待审 / 已拒绝 / 等待
        let cls = 'task-chain-node';
        if (status === 'approved') cls += ' is-done';
        else if (status === 'pending') cls += ' is-current';
        else if (status === 'rejected') cls += ' is-rejected';
        else cls += ' is-waiting';
        return `<span class="${cls}" title="${escapeTaskHtml(status)}">${label}</span>`;
    }).join('<span class="task-chain-arrow">→</span>');
}

function resolveModuleServiceName(m) {
    if (!m) return '-';
    if (m.serviceName) return m.serviceName;
    if (m.serviceCode) return m.serviceCode;
    const image = m.imageFullName || (typeof m === 'string' ? m : '');
    if (!image) return '-';
    const last = String(image).split('/').pop() || '';
    const code = last.split(':')[0];
    return code || '-';
}

function renderModulesCellHtml(task) {
    const details = (task.deployModuleDetails && task.deployModuleDetails.length)
        ? task.deployModuleDetails
        : (task.deployModules || []).map(imageFullName => ({ imageFullName }));
    if (!details.length) {
        return '<span class="task-modules-empty">-</span>';
    }

    const renderOne = (m, idx) => {
        const name = escapeTaskHtml(resolveModuleServiceName(m));
        const image = escapeTaskHtml(m.imageFullName || (typeof m === 'string' ? m : ''));
        const prefix = details.length > 1 ? `${idx + 1}. ` : '';
        return `<div class="task-module-line" title="${image}">${prefix}${name}</div>`;
    };

    if (details.length <= 2) {
        return `<div class="task-modules">${details.map(renderOne).join('')}</div>`;
    }

    const firstName = resolveModuleServiceName(details[0]);
    const summaryId = `task-modules-${task.id}`;
    return `
        <div class="task-modules">
            <button type="button" class="task-modules-toggle" onclick="toggleTaskModules('${summaryId}', this)">
                ${escapeTaskHtml(firstName)} 等 ${details.length} 个 ▾
            </button>
            <div id="${summaryId}" class="task-modules-panel" hidden>
                ${details.map(renderOne).join('')}
            </div>
        </div>
    `;
}

function toggleTaskModules(panelId, btn) {
    const panel = document.getElementById(panelId);
    if (!panel) return;
    const open = panel.hasAttribute('hidden');
    if (open) {
        panel.removeAttribute('hidden');
        if (btn) btn.textContent = btn.textContent.replace('▾', '▴');
    } else {
        panel.setAttribute('hidden', '');
        if (btn) btn.textContent = btn.textContent.replace('▴', '▾');
    }
}
window.toggleTaskModules = toggleTaskModules;

function renderClusterCell(task) {
    const cluster = escapeTaskHtml(task.clusterName || '-');
    return `<div class="task-env-main" title="${cluster}">${cluster}</div>`;
}

function renderEnvCell(task) {
    const env = task.k8sNamespace
        || (task.deployEnvNames && task.deployEnvNames.length ? task.deployEnvNames.join(', ') : '')
        || '-';
    const text = escapeTaskHtml(env);
    return `<div class="task-env-sub" title="${text}">${text}</div>`;
}

function renderProgressBadge(progress) {
    return `<span class="task-progress-badge tone-${progress.tone}">${escapeTaskHtml(progress.label)}</span>`;
}

function populateTaskClusterFilter(tasks) {
    const select = document.getElementById('taskClusterFilter');
    if (!select) return;
    const current = select.value;
    const names = [];
    (tasks || []).forEach(t => {
        const name = (t.clusterName || '').trim();
        if (name && names.indexOf(name) < 0) names.push(name);
    });
    names.sort();
    select.innerHTML = '<option value="">全部</option>' +
        names.map(n => `<option value="${escapeTaskHtml(n)}">${escapeTaskHtml(n)}</option>`).join('');
    if (current && names.indexOf(current) >= 0) {
        select.value = current;
    }
}

function applyTaskFilters() {
    const tasks = window._taskListCache || [];
    const progressFilter = (document.getElementById('taskProgressFilter') || {}).value || '';
    const clusterFilter = (document.getElementById('taskClusterFilter') || {}).value || '';
    const mineOnly = !!(document.getElementById('taskMineOnlyFilter') || {}).checked;

    const filtered = tasks.filter(task => {
        const progress = resolveTaskProgress(task);
        if (progressFilter && progress.key !== progressFilter) return false;
        if (clusterFilter && (task.clusterName || '') !== clusterFilter) return false;
        // 「待我审批」：仅保留当前用户可操作的审批记录所在任务
        if (mineOnly) {
            const actionable = (task.approvalRecords || []).some(r => r.actionable);
            if (!actionable) return false;
        }
        return true;
    });
    paintTaskTable(filtered);
}
window.applyTaskFilters = applyTaskFilters;

async function loadTasks() {
    const taskList = document.getElementById('taskList');
    if (!taskList) return;
    taskList.innerHTML = '<tr><td colspan="9" style="text-align:center;padding:40px;color:#999;">加载中...</td></tr>';

    try {
        const response = await fetch('/api/task/list');
        if (response.ok) {
            const tasks = await response.json();
            window._taskListCache = tasks || [];
            populateTaskClusterFilter(window._taskListCache);
            if (window._taskListCache.length > 0) {
                applyTaskFilters();
                return;
            }
        }
    } catch (error) {
        console.error('Load tasks error:', error);
    }

    // 接口无数据时尝试使用页面 mock
    if (typeof mockTasks !== 'undefined' && mockTasks.length > 0) {
        window._taskListCache = mockTasks;
        populateTaskClusterFilter(window._taskListCache);
        applyTaskFilters();
        return;
    }

    window._taskListCache = [];
    taskList.innerHTML = '<tr><td colspan="9" style="text-align:center;padding:40px;color:#999;">暂无上线任务</td></tr>';
}

function renderTaskList(tasks) {
    window._taskListCache = tasks || [];
    populateTaskClusterFilter(window._taskListCache);
    applyTaskFilters();
}

function paintTaskTable(tasks) {
    const taskList = document.getElementById('taskList');
    if (!taskList) return;

    if (!tasks || !tasks.length) {
        taskList.innerHTML = '<tr><td colspan="9" style="text-align:center;padding:40px;color:#999;">没有符合条件的上线任务</td></tr>';
        return;
    }

    const sortedTasks = [...tasks].sort((a, b) => {
        const timeA = a.createTime ? new Date(a.createTime).getTime() : 0;
        const timeB = b.createTime ? new Date(b.createTime).getTime() : 0;
        return timeB - timeA;
    });

    taskList.innerHTML = sortedTasks.map(task => {
        const progress = resolveTaskProgress(task);
        // 当前用户可操作的审批记录 → 显示通过/拒绝按钮
        const actionableRecord = (task.approvalRecords || []).find(r => r.actionable);
        const canApprove = typeof hasPermission !== 'function' || hasPermission('deploy:approve');
        const canCreate = typeof hasPermission !== 'function' || hasPermission('deploy:create');
        const approvalActions = actionableRecord && canApprove
            ? `<button class="btn-success btn-sm" onclick="approveApprovalRecord(${actionableRecord.id})">通过</button>
               <button class="btn-danger btn-sm" onclick="rejectApprovalRecord(${actionableRecord.id})">拒绝</button>`
            : '';
        const taskName = escapeTaskHtml(task.taskName || '-');
        const taskNumber = escapeTaskHtml(task.taskNumber || task.jobNumber || '-');
        const creatorName = escapeTaskHtml(task.creatorName || '-');

        return `
            <tr>
                <td>
                    <div class="task-number-cell" title="${taskNumber}">${taskNumber}</div>
                </td>
                <td><div class="task-name-cell" title="${taskName}">${taskName}</div></td>
                <td>${renderClusterCell(task)}</td>
                <td>${renderEnvCell(task)}</td>
                <td>${renderModulesCellHtml(task)}</td>
                <td><div class="task-creator-cell" title="${creatorName}">${creatorName}</div></td>
                <td><div class="task-chain">${renderApprovalChainHtml(task)}</div></td>
                <td>${renderProgressBadge(progress)}</td>
                <td>
                    <div class="table-actions task-row-actions">
                        ${approvalActions}
                        <button class="btn-edit btn-sm" onclick="viewTask(${task.id})">详情</button>
                        ${canCreate ? `<button class="btn-danger btn-sm" onclick="deleteTask(${task.id})">删除</button>` : ''}
                    </div>
                </td>
            </tr>
        `;
    }).join('');
}
window.renderTaskList = renderTaskList;
window.loadTasks = loadTasks;

function isProdTaskEnv(env) {
    if (!env) return false;
    const envType = String(env.envType || '').trim().toLowerCase();
    if (envType) {
        return envType === 'prod';
    }
    return String(env.name || '').trim().toLowerCase() === 'prod';
}

async function showCreateTaskModal() {
    if (typeof hasPermission === 'function' && !hasPermission('deploy:create')) {
        alert('无权限创建上线任务');
        return;
    }
    // 加载服务 / 环境 / 集群 / 审批流 / CD 模版 / Harbor 注册地址 / 通知选项
    let services = [];
    let envs = [];
    let clusters = [];
    let approvals = [];
    let cdPipelines = [];
    let harborRegistry = '';
    let notifyOptions = {
        inboxEnabled: true,
        feishuEnabled: false,
        defaultInbox: true,
        defaultFeishu: false
    };
    try {
        const [serviceResp, envResp, clusterResp, approvalResp, notifyResp, pipelineResp, registryResp] = await Promise.all([
            fetch('/api/service/list'),
            fetch('/api/env/list'),
            fetch('/api/cluster/list'),
            fetch('/api/approval/list?status=1'),
            fetch('/api/system/notify/options'),
            fetch('/api/pipeline/list?type=cd&status=1'),
            fetch('/api/harbor/registry')
        ]);
        if (serviceResp.ok) services = await serviceResp.json() || [];
        if (envResp.ok) envs = await envResp.json() || [];
        if (clusterResp.ok) clusters = await clusterResp.json() || [];
        if (approvalResp.ok) approvals = await approvalResp.json() || [];
        if (notifyResp.ok) {
            notifyOptions = Object.assign(notifyOptions, await notifyResp.json());
        }
        if (pipelineResp.ok) cdPipelines = await pipelineResp.json() || [];
        if (registryResp.ok) {
            const reg = await registryResp.json();
            harborRegistry = (reg && reg.registry) ? String(reg.registry).trim() : '';
        }
    } catch (error) {
        console.error('Load create-task options error:', error);
    }
    if (services.length === 0 && typeof mockServices !== 'undefined') {
        services = mockServices;
    }
    if (envs.length === 0 && typeof mockEnvs !== 'undefined') {
        envs = mockEnvs;
    }
    envs = (envs || []).filter(isProdTaskEnv);
    window._taskCreateEnvsCache = envs;
    clusters = (clusters || []).filter(c => c.status === 1 || c.status == null);
    if (!clusters.length) {
        alert('暂无可用集群，请先在「集群管理」中添加集群');
        return;
    }
    if (!approvals.length) {
        alert('暂无启用的审批流，请先在「审批流程」中创建并启用');
        return;
    }
    if (!cdPipelines.length) {
        alert('暂无可用的 CD 流水线模版，请先在「流水线配置 → 流水线管理」中创建类型为 CD 的模版');
        return;
    }
    if (!envs.length) {
        alert('暂无生产环境，请先在「环境配置」中添加并标记生产环境');
        return;
    }
    if (!harborRegistry) {
        alert('无法获取 Harbor 地址，请先在「凭据与组件」中配置 Harbor');
        return;
    }
    window._taskCreateHarborRegistry = harborRegistry;
    
    const content = `
        <form id="createTaskForm">
            <div class="form-item">
                <label>任务名称 *</label>
                <input type="text" name="taskName" placeholder="例如: 用户服务v1.0.0上线" required>
            </div>
            <div class="form-item">
                <label>上线模块 *（服务 / Harbor 项目 / 版本）</label>
                <div id="deployModulesContainer">
                    ${buildDeployModuleRowHtml(services.map(s => ({ value: s.code || s.name, text: s.code || s.name })))}
                </div>
                <button type="button" class="btn-secondary" onclick="addDeployModule()" style="margin-top:8px;font-size:12px;padding:6px 12px;">添加模块</button>
                <small style="color:#6b7280;display:block;margin-top:6px;">将保存完整镜像地址：${harborRegistry}/项目/服务:版本；串行时按此顺序依次部署，有限并行时按并发数同时部署</small>
            </div>
            <div class="form-item">
                <label>上线集群 *</label>
                <select name="clusterId" id="taskClusterSelect" required onchange="onTaskClusterChange(this)">
                    <option value="">请选择集群</option>
                    ${clusters.map(c => {
                        const server = c.server ? ` (${c.server})` : '';
                        return `<option value="${c.id}">${c.name}${server}</option>`;
                    }).join('')}
                </select>
            </div>
            <div class="form-item">
                <label>Namespace *</label>
                <select name="k8sNamespace" id="taskNamespaceSelect" required disabled>
                    <option value="">请先选择集群</option>
                </select>
                <small style="color:#6b7280;">选项来自该集群关联环境中的命名空间</small>
            </div>
            <div class="form-item">
                <label>部署策略 *</label>
                <select name="deployMode" id="taskDeployModeSelect" required onchange="onTaskDeployModeChange(this)">
                    <option value="serial" selected>串行（默认，按模块顺序逐个部署）</option>
                    <option value="parallel">有限并行（同时部署多个模块）</option>
                </select>
            </div>
            <div class="form-item" id="taskParallelismRow" style="display:none;">
                <label>并发数 *</label>
                <input type="number" name="deployParallelism" id="taskDeployParallelism" min="1" max="20" value="3">
                <small style="color:#6b7280;">同时部署的模块数，范围 1–20，默认 3；任一模块失败将跳过其余待部署模块</small>
            </div>
            <div class="form-item">
                <label>审批流 *</label>
                <select name="approvalFlowId" required>
                    <option value="">请选择审批流</option>
                    ${approvals.map(a => `<option value="${a.id}">${a.name}</option>`).join('')}
                </select>
            </div>
            <div class="form-item">
                <label>CD 流水线模版 *</label>
                <select name="pipelineTemplateId" required>
                    <option value="">请选择 CD 模版</option>
                    ${cdPipelines.map(p => `<option value="${p.id}">${p.name}</option>`).join('')}
                </select>
                <small style="color:#6b7280;">仅显示类型为 CD 的模版；审批全部通过后自动按此模版部署所选镜像</small>
            </div>
            <div class="form-item">
                <label>审批通知方式 *（可多选）</label>
                <div style="display:flex;gap:20px;flex-wrap:wrap;padding:8px 0;">
                    <label style="display:flex;align-items:center;gap:6px;cursor:pointer;${!notifyOptions.inboxEnabled ? 'opacity:0.5;' : ''}">
                        <input type="checkbox" name="notifyInbox" ${notifyOptions.defaultInbox ? 'checked' : ''} ${!notifyOptions.inboxEnabled ? 'disabled' : ''}>
                        站内信（系统内通知）
                    </label>
                    <label style="display:flex;align-items:center;gap:6px;cursor:pointer;${!notifyOptions.feishuEnabled ? 'opacity:0.5;' : ''}">
                        <input type="checkbox" name="notifyFeishu" ${notifyOptions.defaultFeishu ? 'checked' : ''} ${!notifyOptions.feishuEnabled ? 'disabled' : ''}>
                        飞书（仅通知，到系统内审批）
                    </label>
                </div>
                <small style="color:#6b7280;">
                    ${!notifyOptions.inboxEnabled && !notifyOptions.feishuEnabled
                        ? '当前未启用任何通知渠道，请到「集成与通知 → 通知设置」开启'
                        : '渠道开关在「集成与通知 → 通知设置」配置；飞书仅发提醒，请在系统「上线任务」中完成审批'}
                </small>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="3" placeholder="任务描述"></textarea>
            </div>
        </form>
    `;
    
    showModal('创建上线任务', content, async () => {
        const form = document.getElementById('createTaskForm');
        
        const taskName = form.querySelector('[name="taskName"]').value;
        if (!taskName) {
            alert('请输入任务名称');
            return;
        }
        
        // 创建表单提交：遍历模块行，拼接完整镜像地址 registry/project/service:version
        const moduleItems = form.querySelectorAll('.deploy-module-item');
        const deployModules = [];
        const registry = (window._taskCreateHarborRegistry || '').replace(/\/+$/, '');
        for (let item of moduleItems) {
            const serviceCode = item.querySelector('.module-service').value;
            const project = item.querySelector('.module-project').value;
            const version = item.querySelector('.module-version').value;
            if (serviceCode && project && version) {
                if (!registry) {
                    alert('Harbor 地址未知，无法生成完整镜像地址');
                    return;
                }
                deployModules.push(`${registry}/${project}/${serviceCode}:${version}`);
            }
        }
        if (deployModules.length === 0) {
            alert('请完整选择服务、Harbor 项目和版本');
            return;
        }

        const clusterId = parseInt(form.querySelector('[name="clusterId"]').value, 10);
        const k8sNamespace = (form.querySelector('[name="k8sNamespace"]').value || '').trim();
        if (!clusterId) {
            alert('请选择上线集群');
            return;
        }
        if (!k8sNamespace) {
            alert('请选择 Namespace');
            return;
        }
        const matchedProdEnv = (window._taskCreateEnvsCache || []).some(env =>
            String(env.clusterId) === String(clusterId) && String((env.k8sNamespace || '').trim()) === k8sNamespace
        );
        if (!matchedProdEnv) {
            alert('创建上线任务只能选择生产环境，请检查该集群关联的环境类型');
            return;
        }
        
        const approvalFlowId = parseInt(form.querySelector('[name="approvalFlowId"]').value, 10);
        if (!approvalFlowId) {
            alert('请选择审批流');
            return;
        }
        const pipelineTemplateId = parseInt(form.querySelector('[name="pipelineTemplateId"]').value, 10);
        if (!pipelineTemplateId) {
            alert('请选择 CD 流水线模版');
            return;
        }

        const deployMode = (form.querySelector('[name="deployMode"]').value || 'serial').trim();
        let deployParallelism = 1;
        if (deployMode === 'parallel') {
            deployParallelism = parseInt(form.querySelector('[name="deployParallelism"]').value, 10);
            if (!deployParallelism || deployParallelism < 1 || deployParallelism > 20) {
                alert('并发数须为 1–20 的整数');
                return;
            }
        }

        const notifyChannels = [];
        const inboxCb = form.querySelector('[name="notifyInbox"]');
        const feishuCb = form.querySelector('[name="notifyFeishu"]');
        if (inboxCb && inboxCb.checked && !inboxCb.disabled) {
            notifyChannels.push('inbox');
        }
        if (feishuCb && feishuCb.checked && !feishuCb.disabled) {
            notifyChannels.push('feishu');
        }
        if (!notifyChannels.length) {
            alert('请至少选择一种已启用的审批通知方式（可在「集成与通知 → 通知设置」开启）');
            return;
        }
        
        const data = {
            taskName: taskName,
            deployModules: deployModules,
            clusterId: clusterId,
            k8sNamespace: k8sNamespace,
            approvalFlowId: approvalFlowId,
            pipelineTemplateId: pipelineTemplateId,
            deployMode: deployMode,
            deployParallelism: deployParallelism,
            notifyChannels: notifyChannels,
            description: form.querySelector('[name="description"]').value || ''
        };
        
        try {
            const response = await fetch('/api/task/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                loadTasks();
                if (typeof refreshInboxBadge === 'function') refreshInboxBadge();
                closeModal();
            } else {
                let errText = await response.text();
                try {
                    const errJson = JSON.parse(errText);
                    errText = errJson.message || errJson.error || errText;
                } catch (e) { /* ignore */ }
                alert('创建失败: ' + (errText || '未知错误'));
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
    refreshDeployModuleOrderButtons();
}

function onTaskDeployModeChange(selectEl) {
    const row = document.getElementById('taskParallelismRow');
    if (!row) return;
    row.style.display = (selectEl && selectEl.value === 'parallel') ? '' : 'none';
}

function onTaskClusterChange(clusterSelect) {
    const nsSelect = document.getElementById('taskNamespaceSelect');
    if (!nsSelect) return;
    const clusterId = clusterSelect.value;
    if (!clusterId) {
        nsSelect.innerHTML = '<option value="">请先选择集群</option>';
        nsSelect.disabled = true;
        return;
    }
    const envs = window._taskCreateEnvsCache || [];
    const namespaces = [];
    envs.forEach(env => {
        if (String(env.clusterId) !== String(clusterId)) return;
        const ns = (env.k8sNamespace || '').trim();
        if (ns && namespaces.indexOf(ns) < 0) {
            namespaces.push(ns);
        }
    });
    if (!namespaces.length) {
        nsSelect.innerHTML = '<option value="">该集群暂无生产环境 Namespace，请先在环境配置中设置生产环境</option>';
        nsSelect.disabled = true;
        return;
    }
    nsSelect.innerHTML = '<option value="">请选择 Namespace</option>' +
        namespaces.map(ns => `<option value="${ns}">${ns}</option>`).join('');
    nsSelect.disabled = false;
    if (namespaces.length === 1) {
        nsSelect.value = namespaces[0];
    }
}
window.onTaskClusterChange = onTaskClusterChange;

async function onDeployModuleServiceChange(serviceSelect) {
    const moduleItem = serviceSelect.closest('.deploy-module-item');
    if (!moduleItem) return;
    const projectSelect = moduleItem.querySelector('.module-project');
    const versionSelect = moduleItem.querySelector('.module-version');
    const serviceName = serviceSelect.value;

    versionSelect.innerHTML = '<option value="">请先选择项目</option>';
    versionSelect.disabled = true;

    if (!serviceName) {
        projectSelect.innerHTML = '<option value="">请先选择服务</option>';
        projectSelect.disabled = true;
        return;
    }

    projectSelect.innerHTML = '<option value="">加载中...</option>';
    projectSelect.disabled = true;
    try {
        const response = await fetch('/api/harbor/projects');
        if (!response.ok) {
            throw new Error(await response.text() || '加载项目失败');
        }
        const projects = await response.json() || [];
        if (!projects.length) {
            projectSelect.innerHTML = '<option value="">暂无 Harbor 项目</option>';
            projectSelect.disabled = true;
            return;
        }
        projectSelect.innerHTML = '<option value="">请选择 Harbor 项目</option>' +
            projects.map(p => {
                const selected = String(p) === 'prod' ? ' selected' : '';
                return `<option value="${p}"${selected}>${p}</option>`;
            }).join('');
        projectSelect.disabled = false;

        // 默认优先选中 prod，并自动加载版本
        const hasProd = projects.some(p => String(p) === 'prod');
        if (hasProd) {
            projectSelect.value = 'prod';
            await onDeployModuleProjectChange(projectSelect);
        } else if (projects.length === 1) {
            projectSelect.value = projects[0];
            await onDeployModuleProjectChange(projectSelect);
        }
    } catch (error) {
        console.error('Load harbor projects error:', error);
        projectSelect.innerHTML = `<option value="">加载失败</option>`;
        projectSelect.disabled = true;
        alert('加载 Harbor 项目失败: ' + (error.message || '未知错误'));
    }
}
window.onDeployModuleServiceChange = onDeployModuleServiceChange;

async function onDeployModuleProjectChange(projectSelect) {
    const moduleItem = projectSelect.closest('.deploy-module-item');
    if (!moduleItem) return;
    const serviceSelect = moduleItem.querySelector('.module-service');
    const versionSelect = moduleItem.querySelector('.module-version');
    const serviceName = serviceSelect.value;
    const project = projectSelect.value;

    if (!serviceName || !project) {
        versionSelect.innerHTML = '<option value="">请先选择项目</option>';
        versionSelect.disabled = true;
        return;
    }

    versionSelect.innerHTML = '<option value="">加载中...</option>';
    versionSelect.disabled = true;
    try {
        const response = await fetch(`/api/harbor/service/${encodeURIComponent(serviceName)}/versions?project=${encodeURIComponent(project)}`);
        if (!response.ok) {
            throw new Error(await response.text() || '加载版本失败');
        }
        const versions = await response.json() || [];
        if (!versions.length) {
            versionSelect.innerHTML = '<option value="">该项目下暂无此服务镜像版本</option>';
            versionSelect.disabled = true;
            return;
        }
        versionSelect.innerHTML = '<option value="">请选择版本</option>' +
            versions.map(v => `<option value="${v}">${v}</option>`).join('');
        versionSelect.disabled = false;
    } catch (error) {
        console.error('Load service versions error:', error);
        versionSelect.innerHTML = '<option value="">加载失败</option>';
        versionSelect.disabled = true;
        alert('加载镜像版本失败: ' + (error.message || '未知错误'));
    }
}
window.onDeployModuleProjectChange = onDeployModuleProjectChange;

function buildDeployModuleRowHtml(services) {
    return `
        <div class="deploy-module-item" style="display:flex;gap:8px;margin-bottom:8px;align-items:center;flex-wrap:wrap;">
            <span class="module-order" style="min-width:20px;text-align:center;font-size:12px;color:#6b7280;font-weight:600;">1</span>
            <div class="module-order-actions" style="display:flex;flex-direction:row;gap:4px;align-items:center;white-space:nowrap;">
                <button type="button" class="btn-secondary module-move-up" onclick="moveDeployModule(this, -1)" style="padding:2px 8px;font-size:11px;line-height:1;" title="上移">↑</button>
                <button type="button" class="btn-secondary module-move-down" onclick="moveDeployModule(this, 1)" style="padding:2px 8px;font-size:11px;line-height:1;" title="下移">↓</button>
            </div>
            <select name="serviceCode" class="module-service" style="flex:1;min-width:120px;" onchange="onDeployModuleServiceChange(this)">
                <option value="">请选择服务</option>
                ${services.map(s => `<option value="${s.value}">${s.text}</option>`).join('')}
            </select>
            <span>/</span>
            <select name="harborProject" class="module-project" style="flex:1;min-width:120px;" disabled onchange="onDeployModuleProjectChange(this)">
                <option value="">请先选择服务</option>
            </select>
            <span>:</span>
            <select name="version" class="module-version" style="flex:1;min-width:120px;" disabled>
                <option value="">请先选择项目</option>
            </select>
            <button type="button" class="btn-danger" onclick="removeDeployModule(this)" style="padding:6px 12px;">删除</button>
        </div>
    `;
}

function getDeployModuleServiceOptions() {
    const container = document.getElementById('deployModulesContainer');
    const firstServiceSelect = container ? container.querySelector('.module-service') : null;
    if (!firstServiceSelect) return [];
    return Array.from(firstServiceSelect.options)
        .map(opt => ({ value: opt.value, text: opt.textContent }))
        .filter(opt => opt.value);
}

function refreshDeployModuleOrderButtons() {
    const container = document.getElementById('deployModulesContainer');
    if (!container) return;
    const items = container.querySelectorAll('.deploy-module-item');
    items.forEach((item, index) => {
        const orderEl = item.querySelector('.module-order');
        if (orderEl) orderEl.textContent = String(index + 1);
        const upBtn = item.querySelector('.module-move-up');
        const downBtn = item.querySelector('.module-move-down');
        if (upBtn) upBtn.disabled = index === 0;
        if (downBtn) downBtn.disabled = index === items.length - 1;
    });
}

function moveDeployModule(btn, direction) {
    const item = btn.closest('.deploy-module-item');
    const container = document.getElementById('deployModulesContainer');
    if (!item || !container) return;
    const items = Array.from(container.querySelectorAll('.deploy-module-item'));
    const index = items.indexOf(item);
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= items.length) return;
    if (direction < 0) {
        container.insertBefore(item, items[targetIndex]);
    } else {
        container.insertBefore(items[targetIndex], item);
    }
    refreshDeployModuleOrderButtons();
}
window.moveDeployModule = moveDeployModule;

async function loadServiceVersions(serviceSelect) {
    return onDeployModuleServiceChange(serviceSelect);
}

function addDeployModule() {
    const container = document.getElementById('deployModulesContainer');
    if (!container) return;

    const services = getDeployModuleServiceOptions();
    if (!services.length) return;

    container.insertAdjacentHTML('beforeend', buildDeployModuleRowHtml(services));
    refreshDeployModuleOrderButtons();
}

function removeDeployModule(btn) {
    const container = document.getElementById('deployModulesContainer');
    const item = btn.closest('.deploy-module-item');
    if (!container || !item) return;
    const items = container.querySelectorAll('.deploy-module-item');
    if (items.length <= 1) {
        alert('至少保留一个上线模块');
        return;
    }
    item.remove();
    refreshDeployModuleOrderButtons();
}

function toggleEnvDropdown(event) {
    event.stopPropagation();
    const dropdown = document.getElementById('envDropdown');
    const isVisible = dropdown.style.display !== 'none';
    dropdown.style.display = isVisible ? 'none' : 'block';
    
    // 点击外部关闭下拉框
    if (!isVisible) {
        setTimeout(() => {
            document.addEventListener('click', function closeDropdown(e) {
                if (!dropdown.contains(e.target) && !e.target.closest('.tag-input-container')) {
                    dropdown.style.display = 'none';
                    document.removeEventListener('click', closeDropdown);
                }
            });
        }, 0);
    }
}

function toggleEnvTag(checkbox) {
    const envId = checkbox.value;
    const envName = checkbox.dataset.name;
    const isChecked = checkbox.checked;
    
    const display = document.getElementById('envTagsDisplay');
    const input = document.getElementById('deployEnvsInput');
    
    if (isChecked) {
        // 添加标签
        const tagHtml = `
            <span class="tag-item" data-env-id="${envId}">
                ${envName}
                <span class="tag-item-remove" onclick="removeEnvTag(event, '${envId}')">×</span>
            </span>
        `;
        // 移除占位符
        const placeholder = display.querySelector('.tag-placeholder');
        if (placeholder) {
            placeholder.remove();
        }
        display.insertAdjacentHTML('beforeend', tagHtml);
        
        // 更新隐藏输入框
        const currentIds = input.value ? input.value.split(',') : [];
        if (!currentIds.includes(envId)) {
            currentIds.push(envId);
            input.value = currentIds.join(',');
        }
    } else {
        // 移除标签
        const tag = display.querySelector(`.tag-item[data-env-id="${envId}"]`);
        if (tag) {
            tag.remove();
        }
        
        // 更新隐藏输入框
        const currentIds = input.value ? input.value.split(',').filter(id => id !== envId) : [];
        input.value = currentIds.join(',');
        
        // 如果没有标签了，显示占位符
        if (display.children.length === 0) {
            display.innerHTML = '<span class="tag-placeholder">请选择部署环境</span>';
        }
    }
}

function removeEnvTag(event, envId) {
    event.stopPropagation();
    
    const display = document.getElementById('envTagsDisplay');
    const input = document.getElementById('deployEnvsInput');
    const dropdown = document.getElementById('envDropdown');
    
    // 移除标签
    const tag = display.querySelector(`.tag-item[data-env-id="${envId}"]`);
    if (tag) {
        tag.remove();
    }
    
    // 取消复选框选中
    const checkbox = dropdown.querySelector(`input[value="${envId}"]`);
    if (checkbox) {
        checkbox.checked = false;
    }
    
    // 更新隐藏输入框
    const currentIds = input.value ? input.value.split(',').filter(id => id !== envId) : [];
    input.value = currentIds.join(',');
    
    // 如果没有标签了，显示占位符
    if (display.children.length === 0) {
        display.innerHTML = '<span class="tag-placeholder">请选择部署环境</span>';
    }
}

async function refreshHarborImages() {
    const form = document.getElementById('createTaskForm');
    if (!form) return;
    
    const imageContainer = form.querySelector('div[style*="max-height"]');
    if (!imageContainer) return;
    
    imageContainer.innerHTML = '<div style="padding: 20px; text-align: center; color: #999;">加载中...</div>';
    
    try {
        const response = await fetch('/api/harbor/images');
        if (response.ok) {
            const images = await response.json();
            if (images && images.length > 0) {
                imageContainer.innerHTML = images.map((img, index) => `
                    <label style="display: block; padding: 4px 0; cursor: pointer;">
                        <input type="checkbox" name="dockerImages" value="${img}" style="margin-right: 8px;">
                        <span style="font-family: monospace; font-size: 13px;">${img}</span>
                    </label>
                `).join('');
            } else {
                imageContainer.innerHTML = '<div style="padding: 20px; text-align: center; color: #999;">暂无镜像</div>';
            }
        } else {
            imageContainer.innerHTML = '<div style="padding: 20px; text-align: center; color: #dc2626;">加载失败</div>';
        }
    } catch (error) {
        imageContainer.innerHTML = '<div style="padding: 20px; text-align: center; color: #dc2626;">加载失败: ' + error.message + '</div>';
    }
}

async function editTask(id) {
    alert('编辑任务功能开发中，ID: ' + id);
}

async function viewTask(id) {
    try {
        const response = await fetch(`/api/task/${id}`);
        if (response.ok) {
            const task = await response.json();
            // 详情中展示模块：优先 deployModuleDetails（含端口），否则纯镜像字符串
            const modulesHtml = (task.deployModuleDetails && task.deployModuleDetails.length)
                ? `<p><strong>上线模块（按部署顺序）:</strong></p>
                <ul style="margin: 8px 0; padding-left: 20px;">
                    ${task.deployModuleDetails.map((m, idx) => `
                        <li style="font-size: 13px; margin: 6px 0;">
                            <div style="font-family: monospace;">${idx + 1}. ${m.imageFullName || '-'}</div>
                            <div style="color:#6b7280;font-size:12px;margin-top:2px;">
                                服务: ${m.serviceName || m.serviceCode || '-'}
                                · 端口: ${m.servicePort != null ? m.servicePort : '-'}
                            </div>
                        </li>
                    `).join('')}
                </ul>`
                : (task.deployModules && task.deployModules.length > 0 ?
                `<p><strong>上线模块（按部署顺序）:</strong></p>
                <ul style="margin: 8px 0; padding-left: 20px;">
                    ${task.deployModules.map((module, idx) => `<li style="font-family: monospace; font-size: 13px; margin: 4px 0;">${idx + 1}. ${module}</li>`).join('')}
                </ul>` :
                '<p><strong>上线模块:</strong> 无</p>');
            
            const clusterHtml = `<p><strong>上线集群:</strong> ${task.clusterName || '-'}</p>
                <p><strong>Namespace:</strong> ${task.k8sNamespace || '-'}</p>`;

            const approvalStatus = (typeof renderApprovalStatusText === 'function')
                ? renderApprovalStatusText(task.approvalStatus)
                : (task.approvalStatus || '-');
            const recordsHtml = (typeof renderApprovalRecordsHtml === 'function')
                ? renderApprovalRecordsHtml(task.approvalRecords || [])
                : '';
            
            const content = `
                <div style="max-height:70vh;overflow-y:auto;">
                    <p><strong>任务编号:</strong> ${task.taskNumber}</p>
                    <p><strong>任务名称:</strong> ${task.taskName || '-'}</p>
                    ${modulesHtml}
                    ${clusterHtml}
                    <p><strong>审批流:</strong> ${task.approvalFlowName || '-'}</p>
                    <p><strong>CD 流水线:</strong> ${task.pipelineTemplateName || '-'}</p>
                    <p><strong>部署策略:</strong> ${formatDeployModeText(task.deployMode, task.deployParallelism)}</p>
                    <p><strong>通知方式:</strong> ${formatNotifyChannelsText(task.notifyChannels)}</p>
                    <p><strong>任务状态:</strong> ${task.taskStatus}</p>
                    <p><strong>审批状态:</strong> ${approvalStatus}</p>
                    <p><strong>描述:</strong> ${task.description || '-'}</p>
                    <p><strong>创建人:</strong> ${task.creatorName || '-'}</p>
                    <p><strong>创建时间:</strong> ${task.createTime ? new Date(task.createTime).toLocaleString('zh-CN') : '-'}</p>
                    ${task.buildJobIds && task.buildJobIds.length ? `
                    <p><strong>CD 执行任务:</strong> ${task.buildJobIds.map(id => `#${id}`).join(', ')}</p>` : ''}
                    <div style="margin-top:16px;">
                        <strong>审批进度</strong>
                        ${recordsHtml}
                    </div>
                </div>
            `;
            showModal('任务详情', content, null);
        }
    } catch (error) {
        alert('加载失败: ' + error.message);
    }
}

async function deleteTask(id) {
    if (!confirm('确定要删除这个任务吗？')) return;
    
    try {
        const response = await fetch(`/api/task/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadTasks();
        } else {
            alert('删除失败');
        }
    } catch (error) {
        alert('删除失败: ' + error.message);
    }
}

