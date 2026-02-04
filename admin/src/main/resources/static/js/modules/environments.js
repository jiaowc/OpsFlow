// ============================================
// 环境配置模块
// ============================================

// 加载环境列表（表格）
async function loadEnvironments() {
    const envList = document.getElementById('envList');
    if (!envList) return;
    
    try {
        const response = await fetch('/api/env/list');
        if (response.ok) {
            const envs = await response.json();
            if (envs && envs.length > 0) {
                renderEnvList(envs);
                return;
            }
        }
    } catch (error) {
        console.error('Load environments error:', error);
    }
    
    renderEnvList(mockEnvs);
}

function renderEnvList(envs) {
    const envList = document.getElementById('envList');
    if (!envList) return;
    
    if (envs.length === 0) {
        envList.innerHTML = '<tr><td colspan="7" style="text-align: center; padding: 40px; color: #999;">暂无环境</td></tr>';
        return;
    }
    
    envList.innerHTML = envs.map(env => `
        <tr>
            <td>${env.name}</td>
            <td>${env.k8sCluster || '-'}</td>
            <td>${env.k8sNamespace || '-'}</td>
            <td>${env.harborProject || '-'}</td>
            <td>${env.jenkinsJobTemplate || '-'}</td>
            <td>${env.status === 1 ? '<span style="color: #059669;">启用</span>' : '<span style="color: #dc2626;">禁用</span>'}</td>
            <td>
                <button class="btn-edit" onclick="editEnv(${env.id})">修改</button>
                <button class="btn-danger" onclick="deleteEnv(${env.id})">删除</button>
            </td>
        </tr>
    `).join('');
}

// 显示添加环境模态框
function showCreateEnvModal() {
    const content = `
        <form id="createEnvForm">
            <div class="form-item">
                <label>环境名称 *</label>
                <input type="text" name="name" placeholder="例如: dev, uat, prod" required>
            </div>
            <div class="form-item">
                <label>K8s集群</label>
                <input type="text" name="k8sCluster">
            </div>
            <div class="form-item">
                <label>K8s命名空间</label>
                <input type="text" name="k8sNamespace">
            </div>
            <div class="form-item">
                <label>Harbor项目</label>
                <input type="text" name="harborProject">
            </div>
        </form>
    `;
    
    showModal('添加环境', content, async () => {
        const form = document.getElementById('createEnvForm');
        const formData = new FormData(form);
        const data = Object.fromEntries(formData.entries());
        
        try {
            const response = await fetch('/api/env/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                loadEnvironments();
                loadEnvs();
                closeModal();
            } else {
                alert('创建失败');
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}

// 编辑环境
async function editEnv(id) {
    alert('编辑环境功能开发中');
}

// 删除环境
async function deleteEnv(id) {
    if (!confirm('确定要删除这个环境吗？')) return;
    try {
        const response = await fetch(`/api/env/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadEnvironments();
            loadEnvs();
        }
    } catch (error) {
        alert('删除失败');
    }
}


