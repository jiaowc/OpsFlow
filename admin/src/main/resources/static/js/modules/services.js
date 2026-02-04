// ============================================
// 服务管理模块
// ============================================

// 加载服务列表（表格）
async function loadServices() {
    const serviceList = document.getElementById('serviceList');
    if (!serviceList) return;
    
    try {
        const response = await fetch('/api/service/list');
        if (response.ok) {
            const services = await response.json();
            if (services && services.length > 0) {
                renderServiceList(services);
                return;
            }
        }
    } catch (error) {
        console.error('Load services error:', error);
    }
    
    renderServiceList(mockServices);
}

function renderServiceList(services) {
    const serviceList = document.getElementById('serviceList');
    if (!serviceList) return;
    
    if (services.length === 0) {
        serviceList.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 40px; color: #999;">暂无服务</td></tr>';
        return;
    }
    
    serviceList.innerHTML = services.map(service => `
        <tr>
            <td>${service.code || '-'}</td>
            <td>${service.gitRepo || '-'}</td>
            <td>${service.defaultBranch || '-'}</td>
            <td>${service.name || '-'}</td>
            <td>${service.status === 1 ? '<span style="color: #059669;">启用</span>' : '<span style="color: #dc2626;">禁用</span>'}</td>
            <td>
                <button class="btn-edit" onclick="editService(${service.id})">修改</button>
                <button class="btn-danger" onclick="deleteService(${service.id})">删除</button>
            </td>
        </tr>
    `).join('');
}

// 显示添加服务模态框
function showCreateServiceModal() {
    const content = `
        <form id="createServiceForm">
            <div class="form-item">
                <label>服务名称 *</label>
                <input type="text" name="name" required>
            </div>
            <div class="form-item">
                <label>服务代码 *</label>
                <input type="text" name="code" required>
            </div>
            <div class="form-item">
                <label>Git仓库</label>
                <input type="text" name="gitRepo">
            </div>
            <div class="form-item">
                <label>默认分支</label>
                <input type="text" name="defaultBranch" value="develop">
            </div>
            <div class="form-item">
                <label>K8s命名空间</label>
                <input type="text" name="k8sNamespace">
            </div>
        </form>
    `;
    
    showModal('添加服务', content, async () => {
        const form = document.getElementById('createServiceForm');
        const formData = new FormData(form);
        const data = Object.fromEntries(formData.entries());
        
        try {
            const response = await fetch('/api/service/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                loadServices();
                loadServicesForSelect();
                closeModal();
            } else {
                alert('创建失败');
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}

// 编辑服务
async function editService(id) {
    alert('编辑服务功能开发中');
}

// 删除服务
async function deleteService(id) {
    if (!confirm('确定要删除这个服务吗？')) return;
    try {
        const response = await fetch(`/api/service/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadServices();
            loadServicesForSelect();
        }
    } catch (error) {
        alert('删除失败');
    }
}


