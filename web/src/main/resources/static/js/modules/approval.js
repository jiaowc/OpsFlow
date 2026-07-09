// ============================================
// 审批流程模块
// ============================================

// 加载审批流列表
async function loadApprovalFlows() {
    const approvalList = document.getElementById('approvalList');
    if (!approvalList) return;
    
    try {
        const response = await fetch('/api/approval/list');
        if (response.ok) {
            const approvals = await response.json();
            if (approvals && approvals.length > 0) {
                renderApprovalList(approvals);
                return;
            }
        }
    } catch (error) {
        console.error('Load approvals error:', error);
    }
    
    renderApprovalList(mockApprovals);
}

function renderApprovalList(approvals) {
    const approvalList = document.getElementById('approvalList');
    if (!approvalList) return;
    
    if (approvals.length === 0) {
        approvalList.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 40px; color: #999;">暂无审批流</td></tr>';
        return;
    }
    
    approvalList.innerHTML = approvals.map(approval => {
        const steps = approval.steps || [];
        return `
            <tr>
                <td>${approval.name}</td>
                <td>${approval.description || '-'}</td>
                <td>${steps.length}</td>
                <td>${approval.status === 1 ? '<span style="color: #059669;">启用</span>' : '<span style="color: #dc2626;">禁用</span>'}</td>
                <td>
                    <button class="btn-edit" onclick="editApproval(${approval.id})">修改</button>
                    <button class="btn-danger" onclick="deleteApproval(${approval.id})">删除</button>
                </td>
            </tr>
        `;
    }).join('');
}

// 显示创建审批流模态框
function showCreateApprovalModal() {
    const content = `
        <form id="createApprovalForm">
            <div class="form-item">
                <label>流程名称 *</label>
                <input type="text" name="name" required>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="3"></textarea>
            </div>
            <div class="form-item">
                <label>审批步骤（JSON格式）</label>
                <textarea name="steps" rows="5" placeholder='[{"step":1,"approver":"user1","approverName":"审批人1","required":true}]'></textarea>
            </div>
        </form>
    `;
    
    showModal('创建审批流', content, async () => {
        const form = document.getElementById('createApprovalForm');
        const formData = new FormData(form);
        const data = Object.fromEntries(formData.entries());
        
        try {
            data.steps = JSON.parse(data.steps);
            const response = await fetch('/api/approval/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                loadApprovalFlows();
                closeModal();
            } else {
                alert('创建失败');
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}

// 编辑审批流
async function editApproval(id) {
    alert('编辑审批流功能开发中');
}

// 删除审批流
async function deleteApproval(id) {
    if (!confirm('确定要删除这个审批流吗？')) return;
    try {
        const response = await fetch(`/api/approval/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadApprovalFlows();
        }
    } catch (error) {
        alert('删除失败');
    }
}


