// ============================================
// 构建Job模块
// ============================================

// 加载构建Job列表
async function loadBuildJobs() {
    try {
        // 先加载环境列表
        const envsResponse = await fetch('/api/build/envs');
        let envs = [];
        if (envsResponse.ok) {
            envs = await envsResponse.json();
        } else {
            // 使用模拟数据
            envs = mockEnvs.filter(e => e.name !== 'prod');
        }
        
        if (envs.length === 0) {
            document.getElementById('envTabs').innerHTML = '<div style="padding: 40px; text-align: center; color: #999;">暂无环境</div>';
            return;
        }
        
        // 生成环境标签页
        const envTabs = document.getElementById('envTabs');
        const envTabContent = document.getElementById('envTabContent');
        
        envTabs.innerHTML = envs.map((env, index) => `
            <button class="env-tab ${index === 0 ? 'active' : ''}" onclick="switchBuildEnvTab(${env.id}, '${env.name}')" data-env-id="${env.id}">
                ${env.name}
                <span class="env-tab-badge" id="badge-${env.id}">0</span>
            </button>
        `).join('');
        
        // 加载第一个环境的任务
        if (envs.length > 0) {
            await loadBuildJobsByEnv(envs[0].id, envs[0].name);
        }
    } catch (error) {
        console.error('Load build jobs error:', error);
    }
}

// 切换环境标签页
async function switchBuildEnvTab(envId, envName) {
    // 更新标签页状态
    document.querySelectorAll('.env-tab').forEach(tab => {
        tab.classList.remove('active');
    });
    document.querySelector(`.env-tab[data-env-id="${envId}"]`).classList.add('active');
    
    // 加载该环境的任务
    await loadBuildJobsByEnv(envId, envName);
}

// 按环境加载构建任务
async function loadBuildJobsByEnv(envId, envName) {
    const envTabContent = document.getElementById('envTabContent');
    envTabContent.innerHTML = '<div style="padding: 40px; text-align: center; color: #999;">加载中...</div>';
    
    try {
        const response = await fetch(`/api/build/jobs?envId=${envId}`);
        let jobs = [];
        if (response.ok) {
            jobs = await response.json();
        }
        
        // 更新标签页徽章
        const badge = document.getElementById(`badge-${envId}`);
        if (badge) {
            badge.textContent = jobs.length;
        }
        
        if (jobs.length === 0) {
            envTabContent.innerHTML = `
                <div style="padding: 40px; text-align: center; color: #999;">
                    <p>${envName} 环境暂无构建任务</p>
                    <button class="btn-primary" onclick="showAddBuildTaskModal()" style="margin-top: 16px;">添加job</button>
                </div>
            `;
            return;
        }
        
        // 渲染任务列表
        envTabContent.innerHTML = `
            <div class="build-job-list">
                ${jobs.map(job => {
                    const statusClass = `status-${job.status || 'pending'}`;
                    const statusText = {
                        'pending': '等待中',
                        'building': '构建中',
                        'success': '成功',
                        'failed': '失败',
                        'deploying': '部署中'
                    }[job.status] || job.status;
                    
                    const createTime = job.createTime ? new Date(job.createTime).toLocaleString('zh-CN') : '-';
                    const startTime = job.startTime ? new Date(job.startTime).toLocaleString('zh-CN') : '-';
                    const endTime = job.endTime ? new Date(job.endTime).toLocaleString('zh-CN') : '-';
                    
                    return `
                        <div class="build-job-item">
                            <div class="build-job-header">
                                <div class="build-job-title">${job.jobNumber} - ${job.serviceName || '未知服务'}</div>
                                <div>
                                    <span class="task-status ${statusClass}">${statusText}</span>
                                </div>
                            </div>
                            <div class="build-job-meta">
                                <span>分支: ${job.branch || '-'}</span>
                                <span>构建节点: ${job.buildNode || '-'}</span>
                                <span>创建时间: ${createTime}</span>
                                ${startTime !== '-' ? `<span>开始时间: ${startTime}</span>` : ''}
                                ${endTime !== '-' ? `<span>结束时间: ${endTime}</span>` : ''}
                                ${job.buildNumber ? `<span>构建号: #${job.buildNumber}</span>` : ''}
                            </div>
                            ${job.buildLogUrl ? `
                                <div style="margin-top: 8px;">
                                    <a href="${job.buildLogUrl}" target="_blank" style="color: #667eea; text-decoration: none; font-size: 13px;">
                                        查看构建日志 →
                                    </a>
                                </div>
                            ` : ''}
                        </div>
                    `;
                }).join('')}
            </div>
        `;
    } catch (error) {
        console.error('Load build jobs by env error:', error);
        envTabContent.innerHTML = '<div style="padding: 40px; text-align: center; color: #dc2626;">加载失败</div>';
    }
}

// 显示添加构建任务模态框
async function showAddBuildTaskModal() {
    // 从服务管理API获取服务列表（与服务管理联动）
    const services = await getServicesList();
    
    if (services.length === 0) {
        alert('暂无可用服务，请先在服务管理中添加服务');
        return;
    }
    
    // 加载Pipeline模板列表
    let pipelines = [];
    try {
        const pipelineResponse = await fetch('/api/pipeline/list');
        if (pipelineResponse.ok) {
            pipelines = await pipelineResponse.json();
        }
    } catch (error) {
        console.error('Load pipelines error:', error);
    }
    
    if (pipelines.length === 0) {
        alert('暂无可用 Pipeline 模版，请先在「系统设置 → 流水线管理」中创建模版');
        return;
    }
    
    // 加载节点列表
    let nodes = [];
    try {
        const nodeResponse = await fetch('/api/node/list');
        if (nodeResponse.ok) {
            nodes = await nodeResponse.json();
        }
    } catch (error) {
        console.error('Load nodes error:', error);
    }
    
    // 加载环境列表
    let envs = [];
    try {
        const envResponse = await fetch('/api/env/list');
        if (envResponse.ok) {
            envs = await envResponse.json();
        }
    } catch (error) {
        console.error('Load envs error:', error);
        envs = mockEnvs || [];
    }
    
    const content = `
        <form id="addBuildTaskForm" style="max-height: 80vh; overflow-y: auto;">
            <div class="form-item">
                <label>Pipeline模板 *</label>
                <select name="pipelineTemplateId" id="pipelineTemplateSelect" required>
                    <option value="">请选择Pipeline模板</option>
                    ${pipelines.map(p => `<option value="${p.id}">${p.name}</option>`).join('')}
                </select>
                <div style="margin-top: 4px; font-size: 12px; color: #666;">
                    提示：Pipeline模板定义了流水线步骤，参数在下方配置
                </div>
            </div>
            
            <h3 style="margin-top: 20px; margin-bottom: 12px; font-size: 14px; color: #333; border-bottom: 1px solid #eee; padding-bottom: 8px;">基础参数</h3>
            
            <div class="form-item">
                <label>服务名称 *</label>
                <select name="serviceId" id="serviceSelect" required>
                    <option value="">请选择服务</option>
                    ${services.map(s => `<option value="${s.id}" data-code="${s.code || ''}" data-git-repo="${s.gitRepo || ''}">${s.code || s.name}</option>`).join('')}
                </select>
                <div style="margin-top: 4px; font-size: 12px; color: #666;">
                    提示：服务列表与服务管理联动，显示的是服务代码（GitLab项目名称）
                </div>
            </div>
            
            <div class="form-item">
                <label>环境 (ENV) *</label>
                <select name="ENV" required>
                    <option value="">请选择环境</option>
                    ${envs.filter(e => e.name !== 'prod').map(e => `<option value="${e.name}">${e.name}</option>`).join('')}
                </select>
            </div>
            
            <div class="form-item">
                <label>构建节点 (node) *</label>
                <select name="node" required>
                    <option value="">请选择构建节点</option>
                    ${nodes.filter(n => n.nodeType === 'build').map(n => `<option value="${n.id}">${n.name}</option>`).join('')}
                </select>
            </div>
            
            <div class="form-item">
                <label>代码分支 (Branch) *</label>
                <input type="text" name="Branch" placeholder="例如: develop" required>
            </div>
            
            <div class="form-item">
                <label>代码地址 (Code_Path) *</label>
                <input type="text" name="Code_Path" id="codePathInput" placeholder="例如: https://gitlab.com/project/repo.git" required>
                <div style="margin-top: 4px; font-size: 12px; color: #666;">
                    提示：选择服务后会自动填充，也可手动修改
                </div>
            </div>
            
            <div class="form-item">
                <label>Harbor地址 (Harbor) *</label>
                <input type="text" name="Harbor" placeholder="例如: https://harbor.example.com" required>
            </div>
            
            <h3 style="margin-top: 20px; margin-bottom: 12px; font-size: 14px; color: #333; border-bottom: 1px solid #eee; padding-bottom: 8px;">部署参数</h3>
            
            <div class="form-item">
                <label>服务端口 (Port)</label>
                <input type="number" name="Port" placeholder="例如: 8080" min="1" max="65535">
            </div>
            
            <div class="form-item">
                <label>副本数 (Replicas)</label>
                <input type="number" name="Replicas" placeholder="例如: 2" min="1" value="1">
            </div>
            
            <h3 style="margin-top: 20px; margin-bottom: 12px; font-size: 14px; color: #333; border-bottom: 1px solid #eee; padding-bottom: 8px;">资源限制</h3>
            
            <div class="form-item">
                <label>CPU限制 (LIMIT_CPU)</label>
                <input type="text" name="LIMIT_CPU" placeholder="例如: 500m" value="500m">
                <div style="margin-top: 4px; font-size: 12px; color: #666;">
                    提示：格式如 500m 或 0.5
                </div>
            </div>
            
            <div class="form-item">
                <label>内存限制 (LIMIT_MEM)</label>
                <input type="text" name="LIMIT_MEM" placeholder="例如: 512Mi" value="512Mi">
                <div style="margin-top: 4px; font-size: 12px; color: #666;">
                    提示：格式如 512Mi 或 1Gi
                </div>
            </div>
            
            <div class="form-item">
                <label>初始CPU分配 (REQ_CPU)</label>
                <input type="text" name="REQ_CPU" placeholder="例如: 100m" value="100m">
            </div>
            
            <div class="form-item">
                <label>初始内存分配 (REQ_MEM)</label>
                <input type="text" name="REQ_MEM" placeholder="例如: 128Mi" value="128Mi">
            </div>
            
            <div class="form-item">
                <label style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" name="autoDeploy" checked> 自动部署
                </label>
            </div>
        </form>
    `;
    
    showModal('添加job', content, async () => {
        const form = document.getElementById('addBuildTaskForm');
        if (!form) {
            alert('表单不存在');
            return;
        }
        
        const formData = new FormData(form);
        
        // 验证必填字段
        const serviceIdStr = formData.get('serviceId');
        if (!serviceIdStr) {
            alert('请选择服务名称');
            return;
        }
        const serviceId = parseInt(serviceIdStr);
        if (isNaN(serviceId)) {
            alert('服务ID无效');
            return;
        }
        
        const selectedService = services.find(s => s.id === serviceId);
        if (!selectedService) {
            alert('所选服务不存在');
            return;
        }
        
        const pipelineTemplateIdStr = formData.get('pipelineTemplateId');
        if (!pipelineTemplateIdStr) {
            alert('请选择Pipeline模板');
            return;
        }
        const pipelineTemplateId = parseInt(pipelineTemplateIdStr);
        if (isNaN(pipelineTemplateId)) {
            alert('Pipeline模板ID无效');
            return;
        }
        
        const envName = formData.get('ENV');
        if (!envName) {
            alert('请选择环境');
            return;
        }
        const env = envs.find(e => e.name === envName);
        if (!env) {
            alert('所选环境不存在');
            return;
        }
        
        const branch = formData.get('Branch');
        if (!branch || !branch.trim()) {
            alert('请输入代码分支');
            return;
        }
        
        const codePath = formData.get('Code_Path');
        if (!codePath || !codePath.trim()) {
            alert('请输入代码地址');
            return;
        }
        
        const harbor = formData.get('Harbor');
        if (!harbor || !harbor.trim()) {
            alert('请输入Harbor地址');
            return;
        }
        
        const nodeStr = formData.get('node');
        if (!nodeStr) {
            alert('请选择构建节点');
            return;
        }
        const nodeId = parseInt(nodeStr);
        if (isNaN(nodeId)) {
            alert('节点ID无效');
            return;
        }
        
        const data = {
            serviceId: serviceId,
            envId: env.id,
            pipelineTemplateId: pipelineTemplateId,
            autoDeploy: formData.get('autoDeploy') === 'on',
            buildParameters: {
                ENV: envName,
                node: nodeId,
                Service_Name: selectedService.code || selectedService.name,
                Branch: branch.trim(),
                Code_Path: codePath.trim(),
                Harbor: harbor.trim(),
                Port: formData.get('Port') ? parseInt(formData.get('Port')) : null,
                Replicas: formData.get('Replicas') ? parseInt(formData.get('Replicas')) : 1,
                LIMIT_CPU: formData.get('LIMIT_CPU') || '500m',
                LIMIT_MEM: formData.get('LIMIT_MEM') || '512Mi',
                REQ_CPU: formData.get('REQ_CPU') || '100m',
                REQ_MEM: formData.get('REQ_MEM') || '128Mi'
            }
        };
        
        try {
            const response = await fetch('/api/build/start', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            const result = await response.json();
            
            if (response.ok) {
                alert('Job已添加！任务编号: ' + result.jobNumber);
                // 重新加载任务列表
                if (data.envId) {
                    const env = envs.find(e => e.id === data.envId);
                    if (env) {
                        await loadBuildJobsByEnv(data.envId, env.name);
                        // 更新所有环境的徽章
                        await loadBuildJobs();
                    }
                }
                closeModal();
            } else {
                alert('添加失败: ' + (result.message || '未知错误'));
            }
        } catch (error) {
            alert('添加失败: ' + error.message);
        }
    });
    
    // 监听服务选择变化，自动填充代码地址
    const serviceSelect = document.getElementById('serviceSelect');
    const codePathInput = document.getElementById('codePathInput');
    if (serviceSelect && codePathInput) {
        serviceSelect.addEventListener('change', function() {
            const selectedOption = this.options[this.selectedIndex];
            const gitRepo = selectedOption.getAttribute('data-git-repo');
            if (gitRepo) {
                codePathInput.value = gitRepo;
            }
        });
    }
}


