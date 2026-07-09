// ============================================
// 上线任务模块
// ============================================

// 加载任务列表
async function loadTasks() {
    const taskList = document.getElementById('taskList');
    if (!taskList) return;
    
    try {
        const response = await fetch('/api/task/list');
        if (response.ok) {
            const tasks = await response.json();
            if (tasks && tasks.length > 0) {
                renderTaskList(tasks);
                return;
            }
        }
    } catch (error) {
        console.error('Load tasks error:', error);
    }
    
    // 使用模拟数据
    if (mockTasks.length === 0) {
        taskList.innerHTML = '<div style="padding: 40px; text-align: center; color: #999;">暂无上线任务</div>';
        return;
    }
    
    renderTaskList(mockTasks);
}

function renderTaskList(tasks) {
    const taskList = document.getElementById('taskList');
    if (!taskList) return;
    
    const sortedTasks = [...tasks].sort((a, b) => {
        const timeA = a.createTime ? new Date(a.createTime).getTime() : 0;
        const timeB = b.createTime ? new Date(b.createTime).getTime() : 0;
        return timeB - timeA;
    });
    
    taskList.innerHTML = sortedTasks.map(task => {
        const statusClass = `status-${task.taskStatus || task.status}`;
        const statusText = {
            'success': '成功',
            'failed': '失败',
            'building': '构建中',
            'deploying': '部署中',
            'pending': '等待中',
            'cancelled': '已取消'
        }[task.taskStatus || task.status] || (task.taskStatus || task.status);
        
        const approvalStatusText = {
            'pending': '待审批',
            'approved': '已通过',
            'rejected': '已拒绝'
        }[task.approvalStatus] || task.approvalStatus || '-';
        
        const createTime = task.createTime ? new Date(task.createTime).toLocaleString('zh-CN') : '-';
        
        return `
            <div class="task-item">
                <div class="task-info">
                    <div class="task-number">${task.taskNumber || task.jobNumber} - ${task.serviceName || '未知服务'}</div>
                    <div class="task-meta">
                        <span>任务名称: ${task.taskName || task.taskNumber || '-'}</span>
                        <span>创建时间: ${createTime}</span>
                        <span>审批状态: ${approvalStatusText}</span>
                        ${task.deployEnvNames && task.deployEnvNames.length > 0 ? 
                            `<span>部署环境: ${task.deployEnvNames.join(', ')}</span>` : ''}
                        ${task.deployModules && task.deployModules.length > 0 ? 
                            `<span>上线模块: ${task.deployModules.length} 个</span>` : ''}
                    </div>
                    ${task.deployModules && task.deployModules.length > 0 ? `
                        <div style="margin-top: 8px; padding: 8px; background: #f5f7fa; border-radius: 4px;">
                            <div style="font-size: 12px; color: #666; margin-bottom: 4px;">上线模块:</div>
                            ${task.deployModules.map(module => `
                                <div style="font-family: monospace; font-size: 12px; color: #333; margin: 2px 0;">${module}</div>
                            `).join('')}
                        </div>
                    ` : ''}
                </div>
                <div style="display: flex; gap: 8px; align-items: center;">
                    <span class="task-status ${statusClass}">${statusText}</span>
                    <button class="btn-edit" onclick="viewTask(${task.id})">查看</button>
                    <button class="btn-edit" onclick="editTask(${task.id})">修改</button>
                    <button class="btn-danger" onclick="deleteTask(${task.id})">删除</button>
                </div>
            </div>
        `;
    }).join('');
}

// 创建上线任务模态框
async function showCreateTaskModal() {
    // 加载服务列表
    let services = [];
    try {
        const response = await fetch('/api/service/list');
        if (response.ok) {
            services = await response.json();
        }
    } catch (error) {
        console.error('Load services error:', error);
    }
    if (services.length === 0) {
        services = mockServices;
    }
    
    const content = `
        <form id="createTaskForm">
            <div class="form-item">
                <label>任务名称 *</label>
                <input type="text" name="taskName" placeholder="例如: 用户服务v1.0.0上线" required>
            </div>
            <div class="form-item">
                <label>上线模块 *（可多选，格式：服务名称:版本号）</label>
                <div id="deployModulesContainer">
                    <div class="deploy-module-item" style="display: flex; gap: 8px; margin-bottom: 8px; align-items: center;">
                        <select name="serviceCode" class="module-service" style="flex: 1;" onchange="loadServiceVersions(this)">
                            <option value="">请选择服务</option>
                            ${services.map(s => `<option value="${s.code || s.name}">${s.code || s.name}</option>`).join('')}
                        </select>
                        <span>:</span>
                        <select name="version" class="module-version" style="flex: 1;" disabled>
                            <option value="">请先选择服务</option>
                        </select>
                        <button type="button" class="btn-danger" onclick="removeDeployModule(this)" style="padding: 6px 12px;">删除</button>
                    </div>
                </div>
                <button type="button" class="btn-secondary" onclick="addDeployModule()" style="margin-top: 8px; font-size: 12px; padding: 6px 12px;">添加模块</button>
            </div>
            <div class="form-item">
                <label>部署环境 *（可多选）</label>
                <div class="tag-selector" style="position: relative;">
                    <div class="tag-input-container" onclick="toggleEnvDropdown(event)">
                        <div class="tag-input-display" id="envTagsDisplay">
                            <span class="tag-placeholder">请选择部署环境</span>
                        </div>
                        <input type="hidden" name="deployEnvs" id="deployEnvsInput">
                        <span class="tag-arrow">▼</span>
                    </div>
                    <div class="tag-dropdown" id="envDropdown" style="display: none;">
                        ${mockEnvs.map(e => `
                            <label class="tag-option">
                                <input type="checkbox" value="${e.id}" data-name="${e.name}" onchange="toggleEnvTag(this)">
                                <span>${e.name}</span>
                            </label>
                        `).join('')}
                    </div>
                </div>
            </div>
            <div class="form-item">
                <label>审批流 *</label>
                <select name="approvalFlowId" required>
                    <option value="">请选择审批流</option>
                    ${mockApprovals.map(a => `<option value="${a.id}">${a.name}</option>`).join('')}
                </select>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="3" placeholder="任务描述"></textarea>
            </div>
        </form>
    `;
    
    showModal('创建上线任务', content, async () => {
        const form = document.getElementById('createTaskForm');
        
        // 获取任务名称
        const taskName = form.querySelector('[name="taskName"]').value;
        if (!taskName) {
            alert('请输入任务名称');
            return;
        }
        
        // 获取上线模块（服务名称:版本号）
        const moduleItems = form.querySelectorAll('.deploy-module-item');
        const deployModules = [];
        for (let item of moduleItems) {
            const serviceCode = item.querySelector('.module-service').value;
            const version = item.querySelector('.module-version').value;
            if (serviceCode && version) {
                deployModules.push(`${serviceCode}:${version}`);
            }
        }
        if (deployModules.length === 0) {
            alert('请至少添加一个上线模块');
            return;
        }
        
        // 获取选中的环境（从标签选择器）
        const envInput = form.querySelector('#deployEnvsInput');
        const deployEnvIdsStr = envInput.value;
        if (!deployEnvIdsStr || deployEnvIdsStr.trim() === '') {
            alert('请至少选择一个部署环境');
            return;
        }
        const deployEnvIds = deployEnvIdsStr.split(',').map(id => parseInt(id)).filter(id => !isNaN(id));
        if (deployEnvIds.length === 0) {
            alert('请至少选择一个部署环境');
            return;
        }
        
        // 获取审批流
        const approvalFlowId = parseInt(form.querySelector('[name="approvalFlowId"]').value);
        if (!approvalFlowId) {
            alert('请选择审批流');
            return;
        }
        
        const data = {
            taskName: taskName,
            deployModules: deployModules,
            deployEnvIds: deployEnvIds,
            approvalFlowId: approvalFlowId,
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
                closeModal();
            } else {
                const error = await response.json();
                alert('创建失败: ' + (error.message || '未知错误'));
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}

// 加载服务版本列表
async function loadServiceVersions(serviceSelect) {
    const serviceName = serviceSelect.value;
    const moduleItem = serviceSelect.closest('.deploy-module-item');
    const versionSelect = moduleItem.querySelector('.module-version');
    
    if (!serviceName) {
        versionSelect.innerHTML = '<option value="">请先选择服务</option>';
        versionSelect.disabled = true;
        return;
    }
    
    versionSelect.innerHTML = '<option value="">加载中...</option>';
    versionSelect.disabled = true;
    
    try {
        const response = await fetch(`/api/harbor/service/${serviceName}/versions`);
        if (response.ok) {
            const versions = await response.json();
            if (versions && versions.length > 0) {
                versionSelect.innerHTML = '<option value="">请选择版本</option>' + 
                    versions.map(v => `<option value="${v}">${v}</option>`).join('');
                versionSelect.disabled = false;
            } else {
                versionSelect.innerHTML = '<option value="">暂无版本</option>';
                versionSelect.disabled = true;
            }
        } else {
            versionSelect.innerHTML = '<option value="">加载失败</option>';
            versionSelect.disabled = true;
        }
    } catch (error) {
        console.error('Load service versions error:', error);
        versionSelect.innerHTML = '<option value="">加载失败</option>';
        versionSelect.disabled = true;
    }
}

// 添加上线模块
function addDeployModule() {
    const container = document.getElementById('deployModulesContainer');
    if (!container) return;
    
    // 获取服务列表（从第一个模块的服务选择框获取）
    const firstServiceSelect = container.querySelector('.module-service');
    if (!firstServiceSelect) return;
    
    const services = Array.from(firstServiceSelect.options)
        .map(opt => ({ value: opt.value, text: opt.textContent }))
        .filter(opt => opt.value);
    
    const moduleHtml = `
        <div class="deploy-module-item" style="display: flex; gap: 8px; margin-bottom: 8px; align-items: center;">
            <select name="serviceCode" class="module-service" style="flex: 1;" onchange="loadServiceVersions(this)">
                <option value="">请选择服务</option>
                ${services.map(s => `<option value="${s.value}">${s.text}</option>`).join('')}
            </select>
            <span>:</span>
            <select name="version" class="module-version" style="flex: 1;" disabled>
                <option value="">请先选择服务</option>
            </select>
            <button type="button" class="btn-danger" onclick="removeDeployModule(this)" style="padding: 6px 12px;">删除</button>
        </div>
    `;
    
    container.insertAdjacentHTML('beforeend', moduleHtml);
}

// 删除上线模块
function removeDeployModule(btn) {
    const item = btn.closest('.deploy-module-item');
    if (item) {
        item.remove();
    }
}

// 切换环境下拉框显示
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

// 切换环境标签
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

// 删除环境标签
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

// 刷新Harbor镜像列表
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

// 编辑任务
async function editTask(id) {
    alert('编辑任务功能开发中，ID: ' + id);
}

// 查看任务详情
async function viewTask(id) {
    try {
        const response = await fetch(`/api/task/${id}`);
        if (response.ok) {
            const task = await response.json();
            const modulesHtml = task.deployModules && task.deployModules.length > 0 ? 
                `<p><strong>上线模块:</strong></p>
                <ul style="margin: 8px 0; padding-left: 20px;">
                    ${task.deployModules.map(module => `<li style="font-family: monospace; font-size: 13px; margin: 4px 0;">${module}</li>`).join('')}
                </ul>` : 
                '<p><strong>上线模块:</strong> 无</p>';
            
            const envsHtml = task.deployEnvNames && task.deployEnvNames.length > 0 ?
                `<p><strong>部署环境:</strong> ${task.deployEnvNames.join(', ')}</p>` :
                '<p><strong>部署环境:</strong> 无</p>';
            
            const content = `
                <div>
                    <p><strong>任务编号:</strong> ${task.taskNumber}</p>
                    <p><strong>任务名称:</strong> ${task.taskName || '-'}</p>
                    ${modulesHtml}
                    ${envsHtml}
                    <p><strong>审批流:</strong> ${task.approvalFlowName || '-'}</p>
                    <p><strong>任务状态:</strong> ${task.taskStatus}</p>
                    <p><strong>审批状态:</strong> ${task.approvalStatus}</p>
                    <p><strong>描述:</strong> ${task.description || '-'}</p>
                    <p><strong>创建时间:</strong> ${task.createTime ? new Date(task.createTime).toLocaleString('zh-CN') : '-'}</p>
                </div>
            `;
            showModal('任务详情', content);
        }
    } catch (error) {
        alert('加载失败: ' + error.message);
    }
}

// 删除任务
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


