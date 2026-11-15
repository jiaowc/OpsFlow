// 审批流配置与待办审批操作（配合上线任务模块）

//
// 职责概览：
// - 审批流 CRUD：列表加载、创建/编辑模态框、删除
// - 审批步骤表单：动态增删步骤、用户下拉、必审开关
// - 审批记录展示：任务详情内嵌表格、通过/拒绝操作
// - 供上线任务模块复用：fetchEnabledApprovalFlows、renderApprovalRecordsHtml 等
// ============================================

async function loadApprovalFlows() {
    const approvalList = document.getElementById('approvalList');
    if (!approvalList) return;
    approvalList.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">加载中...</td></tr>';

    try {
        const response = await fetch('/api/approval/list');
        if (!response.ok) {
            throw new Error(await response.text() || '加载失败');
        }
        const approvals = await response.json();
        renderApprovalList(Array.isArray(approvals) ? approvals : []);
    } catch (error) {
        console.error('Load approvals error:', error);
        approvalList.innerHTML = `<tr><td colspan="5" style="text-align:center;padding:40px;color:#dc2626;">加载失败: ${escApprovalHtml(error.message)}</td></tr>`;
    }
}
window.loadApprovalFlows = loadApprovalFlows;

function escApprovalHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function renderApprovalList(approvals) {
    const approvalList = document.getElementById('approvalList');
    if (!approvalList) return;

    if (!approvals.length) {
        approvalList.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 40px; color: var(--text-4);">暂无审批流，请点击「创建审批流」</td></tr>';
        return;
    }

    approvalList.innerHTML = approvals.map(approval => {
        const steps = approval.steps || [];
        const stepNames = steps.map((s, i) => escApprovalHtml((s.approverName || s.approver || ('步骤' + (s.step || i + 1)))
            + ((s.approvers || []).length > 1 ? '（任一）' : ''))).join(' → ');
        return `
            <tr>
                <td>${escApprovalHtml(approval.name)}</td>
                <td>${escApprovalHtml(approval.description || '-')}</td>
                <td title="${stepNames}">${steps.length}${stepNames ? '（' + stepNames + '）' : ''}</td>
                <td>${approval.status === 1 || approval.status == null
                    ? '<span class="status-tag is-on">启用</span>'
                    : '<span class="status-tag is-off">禁用</span>'}</td>
                <td>
                    ${hasPermission('approval:manage') ? `<div class="table-actions">
                        <button class="btn-edit" onclick="editApproval(${approval.id})">修改</button>
                        <button class="btn-danger" onclick="deleteApproval(${approval.id})">删除</button>
                    </div>` : '<span style="color:#9ca3af;">-</span>'}
                </td>
            </tr>
        `;
    }).join('');
}

async function loadApprovalUsers() {
    try {
        const resp = await fetch('/api/user/options');
        if (resp.ok) {
            const users = await resp.json() || [];
            return users.filter(u => u.status === 1 || u.status == null);
        }
    } catch (e) {
        console.warn('Load users for approval failed', e);
    }
    return [];
}

function buildApprovalApproverListHtml(users, selected) {
    const selectedSet = new Set((selected || []).map(u => String(u).toLowerCase()));
    const known = new Set(users.map(u => String(u.username || '').toLowerCase()));
    const items = users.map(u => ({
        username: u.username || '',
        label: u.realName ? `${u.realName} (${u.username})` : (u.username || ''),
        realName: u.realName || ''
    }));
    (selected || []).filter(u => u && !known.has(String(u).toLowerCase()))
        .forEach(u => items.push({ username: u, label: `${u}（已停用或不存在）`, realName: '' }));
    if (!items.length) {
        return '<div class="checkbox-list-empty">暂无可选用户</div>';
    }
    return items.map(u => `
        <label class="checkbox-list-item" data-search="${escApprovalHtml(u.label.toLowerCase())}">
            <input type="checkbox" class="approval-step-approver" value="${escApprovalHtml(u.username)}"
                data-real-name="${escApprovalHtml(u.realName)}" onchange="onApprovalApproverChange(this)"
                ${selectedSet.has(u.username.toLowerCase()) ? 'checked' : ''}>
            <span>${escApprovalHtml(u.label)}</span>
        </label>`).join('');
}

function buildApprovalStepRowHtml(step, approverListHtml) {
    step = step || {};
    return `
        <div class="approval-step-row" style="display:flex;gap:8px;align-items:flex-start;margin-bottom:8px;padding:10px;background:#f8fafc;border:1px solid #e5e7eb;border-radius:6px;">
            <div style="width:56px;">
                <label style="font-size:12px;color:#6b7280;">步骤</label>
                <input type="number" class="approval-step-no" min="1" value="${step.step || 1}" style="width:100%;">
            </div>
            <div style="flex:1;">
                <label style="font-size:12px;color:#6b7280;">审批人（可多选，任一人审批即可；不选则任意登录用户可审批）</label>
                <input type="text" placeholder="搜索用户" oninput="filterApprovalApprovers(this)" style="margin-bottom:4px;">
                <div class="checkbox-list approval-step-approvers" style="max-height:140px;overflow:auto;">${approverListHtml}</div>
            </div>
            <div style="flex:1;">
                <label style="font-size:12px;color:#6b7280;">显示名</label>
                <input type="text" class="approval-step-approver-name" value="${escApprovalHtml(step.approverName || '')}" placeholder="可选">
            </div>
            <div style="padding-top:22px;">
                <label style="font-size:12px;white-space:nowrap;">
                    <input type="checkbox" class="approval-step-required" ${(step.required === false) ? '' : 'checked'}> 必审
                </label>
            </div>
            <div style="padding-top:18px;">
                <button type="button" class="btn-danger" style="padding:6px 10px;" onclick="removeApprovalStepRow(this)">删除</button>
            </div>
        </div>
    `;
}

function onApprovalApproverChange(checkbox) {
    const row = checkbox.closest('.approval-step-row');
    if (!row) return;
    const nameInput = row.querySelector('.approval-step-approver-name');
    if (!nameInput) return;
    nameInput.value = Array.from(row.querySelectorAll('.approval-step-approver:checked'))
        .map(el => el.getAttribute('data-real-name') || el.value)
        .join(' / ');
}
window.onApprovalApproverChange = onApprovalApproverChange;

function filterApprovalApprovers(input) {
    const keyword = (input.value || '').trim().toLowerCase();
    const row = input.closest('.approval-step-row');
    if (!row) return;
    row.querySelectorAll('.approval-step-approvers .checkbox-list-item').forEach(item => {
        item.style.display = !keyword || item.dataset.search.includes(keyword) ? '' : 'none';
    });
}
window.filterApprovalApprovers = filterApprovalApprovers;

async function addApprovalStepRow(prefill) {
    const container = document.getElementById('approvalStepsContainer');
    if (!container) return;
    const selected = prefill && prefill.approvers && prefill.approvers.length
        ? prefill.approvers
        : (prefill && prefill.approver ? [prefill.approver] : []);
    const users = await loadApprovalUsers();
    const index = container.querySelectorAll('.approval-step-row').length + 1;
    const step = Object.assign({ step: index }, prefill || {});
    container.insertAdjacentHTML('beforeend', buildApprovalStepRowHtml(step, buildApprovalApproverListHtml(users, selected)));
}
window.addApprovalStepRow = addApprovalStepRow;

function removeApprovalStepRow(btn) {
    const row = btn.closest('.approval-step-row');
    if (row) row.remove();
    renumberApprovalSteps();
}
window.removeApprovalStepRow = removeApprovalStepRow;

function renumberApprovalSteps() {
    const rows = document.querySelectorAll('#approvalStepsContainer .approval-step-row');
    rows.forEach((row, i) => {
        const input = row.querySelector('.approval-step-no');
        if (input) input.value = i + 1;
    });
}

function collectApprovalStepsFromForm() {
    const rows = document.querySelectorAll('#approvalStepsContainer .approval-step-row');
    const steps = [];
    rows.forEach((row, i) => {
        const stepNo = parseInt(row.querySelector('.approval-step-no').value, 10) || (i + 1);
        const approvers = Array.from(row.querySelectorAll('.approval-step-approver:checked')).map(el => el.value);
        const approverName = (row.querySelector('.approval-step-approver-name').value || '').trim();
        const required = row.querySelector('.approval-step-required').checked;
        const approver = approvers.length === 1 ? approvers[0] : null;
        steps.push({
            step: stepNo,
            approver,
            approvers: approvers.length > 1 ? approvers : null,
            approverName: approverName || approver,
            required
        });
    });
    return steps;
}

function collectApprovalFormData(form) {
    const name = (form.querySelector('[name="name"]').value || '').trim();
    const description = (form.querySelector('[name="description"]').value || '').trim();
    const statusRaw = form.querySelector('[name="status"]');
    const status = statusRaw && statusRaw.value === '0' ? 0 : 1;
    const steps = collectApprovalStepsFromForm();
    if (!name) {
        alert('请填写流程名称');
        return null;
    }
    if (!steps.length) {
        alert('请至少添加一个审批步骤');
        return null;
    }
    return { name, description, status, steps };
}

async function showCreateApprovalModal() {
    const content = `
        <form id="createApprovalForm" style="max-height:75vh;overflow-y:auto;">
            <div class="form-item">
                <label>流程名称 *</label>
                <input type="text" name="name" placeholder="例如: 生产发布审批" required>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="2" placeholder="流程说明"></textarea>
            </div>
            <div class="form-item">
                <label>状态</label>
                <select name="status">
                    <option value="1" selected>启用</option>
                    <option value="0">禁用</option>
                </select>
            </div>
            <div class="form-item">
                <label style="display:flex;justify-content:space-between;align-items:center;">
                    <span>审批步骤 *</span>
                    <button type="button" class="btn-secondary" style="padding:4px 10px;font-size:12px;" onclick="addApprovalStepRow()">添加步骤</button>
                </label>
                <div id="approvalStepsContainer"></div>
                <small style="color:#6b7280;">按顺序串行审批；未指定审批人时，任意登录用户可处理该步</small>
            </div>
        </form>
    `;

    showModal('创建审批流', content, async () => {
        const form = document.getElementById('createApprovalForm');
        const data = collectApprovalFormData(form);
        if (!data) return;
        try {
            const response = await fetch('/api/approval/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (!response.ok) {
                throw new Error(await response.text() || '创建失败');
            }
            alert('创建成功');
            loadApprovalFlows();
            closeModal();
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });

    setTimeout(() => addApprovalStepRow({ step: 1 }), 50);
}
window.showCreateApprovalModal = showCreateApprovalModal;

async function editApproval(id) {
    try {
        const response = await fetch(`/api/approval/${id}`);
        if (!response.ok) throw new Error('加载失败');
        const flow = await response.json();
        const content = `
            <form id="editApprovalForm" style="max-height:75vh;overflow-y:auto;">
                <div class="form-item">
                    <label>流程名称 *</label>
                    <input type="text" name="name" value="${escApprovalHtml(flow.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>描述</label>
                    <textarea name="description" rows="2">${escApprovalHtml(flow.description || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>状态</label>
                    <select name="status">
                        <option value="1" ${flow.status !== 0 ? 'selected' : ''}>启用</option>
                        <option value="0" ${flow.status === 0 ? 'selected' : ''}>禁用</option>
                    </select>
                </div>
                <div class="form-item">
                    <label style="display:flex;justify-content:space-between;align-items:center;">
                        <span>审批步骤 *</span>
                        <button type="button" class="btn-secondary" style="padding:4px 10px;font-size:12px;" onclick="addApprovalStepRow()">添加步骤</button>
                    </label>
                    <div id="approvalStepsContainer"></div>
                </div>
            </form>
        `;
        showModal('编辑审批流', content, async () => {
            const form = document.getElementById('editApprovalForm');
            const data = collectApprovalFormData(form);
            if (!data) return;
            try {
                const resp = await fetch(`/api/approval/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                if (!resp.ok) throw new Error(await resp.text() || '更新失败');
                alert('更新成功');
                loadApprovalFlows();
                closeModal();
            } catch (e) {
                alert('更新失败: ' + e.message);
            }
        });

        setTimeout(async () => {
            const steps = flow.steps && flow.steps.length ? flow.steps : [{ step: 1 }];
            for (const step of steps) {
                await addApprovalStepRow(step);
            }
        }, 50);
    } catch (error) {
        alert('加载失败: ' + error.message);
    }
}
window.editApproval = editApproval;

async function deleteApproval(id) {
    if (!confirm('确定要删除这个审批流吗？')) return;
    try {
        const response = await fetch(`/api/approval/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadApprovalFlows();
        } else {
            alert('删除失败: ' + (await response.text() || ''));
        }
    } catch (error) {
        alert('删除失败: ' + error.message);
    }
}
window.deleteApproval = deleteApproval;

async function fetchEnabledApprovalFlows() {
    try {
        const response = await fetch('/api/approval/list?status=1');
        if (response.ok) {
            return await response.json() || [];
        }
    } catch (e) {
        console.warn('fetchEnabledApprovalFlows failed', e);
    }
    return [];
}
window.fetchEnabledApprovalFlows = fetchEnabledApprovalFlows;

function renderApprovalStatusText(status) {
    return ({
        pending: '待审批',
        approved: '已通过',
        rejected: '已拒绝',
        none: '无需审批',
        waiting: '等待中',
        cancelled: '已取消'
    })[status] || status || '-';
}
window.renderApprovalStatusText = renderApprovalStatusText;

function renderApprovalRecordsHtml(records) {
    if (!records || !records.length) {
        return '<p style="color:#6b7280;">暂无审批记录</p>';
    }
    const rows = records.map(r => {
        const actions = r.actionable
            ? `<button class="btn-success" style="padding:4px 8px;margin-right:4px;" onclick="approveApprovalRecord(${r.id})">通过</button>
               <button class="btn-danger" style="padding:4px 8px;" onclick="rejectApprovalRecord(${r.id})">拒绝</button>`
            : '-';
        return `
            <tr>
                <td>第 ${r.currentStep} 步</td>
                <td>${escApprovalHtml(r.approverName || r.approver || '任意用户')}</td>
                <td>${renderApprovalStatusText(r.status)}</td>
                <td>${escApprovalHtml(r.comment || '-')}</td>
                <td>${r.approveTime ? new Date(r.approveTime).toLocaleString('zh-CN') : '-'}</td>
                <td>${actions}</td>
            </tr>
        `;
    }).join('');
    return `
        <div class="table-container" style="margin-top:8px;">
            <table class="data-table">
                <thead>
                    <tr>
                        <th>步骤</th>
                        <th>审批人</th>
                        <th>状态</th>
                        <th>意见</th>
                        <th>时间</th>
                        <th>操作</th>
                    </tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
        </div>
    `;
}
window.renderApprovalRecordsHtml = renderApprovalRecordsHtml;

async function approveApprovalRecord(recordId) {
    const comment = prompt('审批意见（可选）:', '同意');
    if (comment === null) return;
    try {
        const response = await fetch(`/api/approval/records/${recordId}/approve`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ comment })
        });
        if (!response.ok) throw new Error(await response.text() || '审批失败');
        alert('已通过');
        if (typeof closeModal === 'function') closeModal();
        if (typeof loadTasks === 'function') loadTasks();
        if (typeof refreshInboxBadge === 'function') refreshInboxBadge();
    } catch (e) {
        alert('审批失败: ' + e.message);
    }
}
window.approveApprovalRecord = approveApprovalRecord;

async function rejectApprovalRecord(recordId) {
    const comment = prompt('拒绝原因:', '');
    if (comment === null) return;
    try {
        const response = await fetch(`/api/approval/records/${recordId}/reject`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ comment })
        });
        if (!response.ok) throw new Error(await response.text() || '操作失败');
        alert('已拒绝');
        if (typeof closeModal === 'function') closeModal();
        if (typeof loadTasks === 'function') loadTasks();
        if (typeof refreshInboxBadge === 'function') refreshInboxBadge();
    } catch (e) {
        alert('操作失败: ' + e.message);
    }
}
window.rejectApprovalRecord = rejectApprovalRecord;
