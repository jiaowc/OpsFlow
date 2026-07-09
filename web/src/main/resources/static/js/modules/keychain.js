// ============================================
// 钥匙串管理模块
// ============================================

const CREDENTIAL_TYPE_OPTIONS = [
    { value: 'username_password', label: '账户密码' },
    { value: 'token', label: 'Token' },
    { value: 'api_key', label: 'API密钥' },
    { value: 'oauth', label: 'OAuth' },
    { value: 'kubeconfig', label: 'K8s 集群' },
    { value: 'ssh_password', label: 'SSH 账户密码' },
    { value: 'ssh_key', label: 'SSH 公钥/私钥' }
];

const COMPONENT_CREDENTIAL_TYPES = {
    gitlab: ['username_password', 'token', 'api_key', 'oauth'],
    github: ['username_password', 'token', 'api_key', 'oauth'],
    harbor: ['username_password', 'token', 'api_key'],
    nexus: ['username_password', 'token', 'api_key'],
    sonarqube: ['username_password', 'token', 'api_key'],
    k8s: ['kubeconfig'],
    kubernetes: ['kubeconfig'],
    other: CREDENTIAL_TYPE_OPTIONS.map(o => o.value)
};

function escKeychainHtml(text) {
    if (text == null) return '';
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function getCredentialTypeLabel(type) {
    const item = CREDENTIAL_TYPE_OPTIONS.find(o => o.value === type);
    return item ? item.label : (type || '-');
}

function renderCredentialTypeOptions(selected) {
    return CREDENTIAL_TYPE_OPTIONS.map(opt => {
        const sel = selected === opt.value ? ' selected' : '';
        return `<option value="${opt.value}"${sel}>${opt.label}</option>`;
    }).join('');
}

function buildCredentialConfigFields(credentialType, configData) {
    configData = configData || {};
    switch (credentialType) {
        case 'username_password':
        case 'ssh_password':
            return `
                <div class="form-item">
                    <label>用户名 *</label>
                    <input type="text" name="username" value="${escKeychainHtml(configData.username || '')}" required>
                </div>
                <div class="form-item">
                    <label>密码 *</label>
                    <input type="password" name="password" placeholder="${configData.password === '******' ? '留空则不修改' : 'GitHub 请填写 PAT，非网页登录密码'}" ${configData.password === '******' ? '' : 'required'}>
                    <small style="color:#6b7280;">GitHub 组件请填写 Personal Access Token，不能使用网页登录密码</small>
                </div>`;
        case 'token':
            return `
                <div class="form-item">
                    <label>Token *</label>
                    <input type="password" name="token" placeholder="${configData.token === '******' ? '留空则不修改' : 'ghp_xxx 或 github_pat_xxx'}" ${configData.token === '******' ? '' : 'required'}>
                    <small style="color:#6b7280;">GitHub PAT 创建：Settings → Developer settings → Personal access tokens</small>
                </div>`;
        case 'api_key':
            return `
                <div class="form-item">
                    <label>API 密钥 *</label>
                    <input type="password" name="apiKey" placeholder="${configData.apiKey === '******' ? '留空则不修改' : ''}" ${configData.apiKey === '******' ? '' : 'required'}>
                </div>`;
        case 'oauth':
            return `
                <div class="form-item">
                    <label>Client ID *</label>
                    <input type="text" name="clientId" value="${escKeychainHtml(configData.clientId || '')}" required>
                </div>
                <div class="form-item">
                    <label>Client Secret *</label>
                    <input type="password" name="clientSecret" placeholder="${configData.clientSecret === '******' ? '留空则不修改' : ''}" ${configData.clientSecret === '******' ? '' : 'required'}>
                </div>`;
        case 'kubeconfig':
            return `
                <div class="form-item">
                    <label>Kubeconfig 文件路径</label>
                    <input type="text" name="configFile" value="${escKeychainHtml(configData.configFile || '')}" placeholder="/path/to/kubeconfig">
                </div>
                <div class="form-item">
                    <label>Kubeconfig 内容</label>
                    <textarea name="configContent" rows="8" placeholder="或直接粘贴 kubeconfig YAML 内容">${configData.configContent === '******' ? '' : escKeychainHtml(configData.configContent || '')}</textarea>
                    <small style="color:#6b7280;">文件路径与内容二选一，内容优先</small>
                </div>`;
        case 'ssh_key':
            return `
                <div class="form-item">
                    <label>SSH 用户名 *</label>
                    <input type="text" name="username" value="${escKeychainHtml(configData.username || '')}" required>
                </div>
                <div class="form-item">
                    <label>私钥 *</label>
                    <textarea name="privateKey" rows="6" placeholder="${configData.privateKey === '******' ? '留空则不修改' : '-----BEGIN OPENSSH PRIVATE KEY-----'}" ${configData.privateKey === '******' ? '' : 'required'}>${configData.privateKey === '******' ? '' : escKeychainHtml(configData.privateKey || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>公钥（可选）</label>
                    <textarea name="publicKey" rows="3" placeholder="ssh-rsa AAAA...">${escKeychainHtml(configData.publicKey || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>私钥口令（可选）</label>
                    <input type="password" name="passphrase" placeholder="${configData.passphrase === '******' ? '留空则不修改' : ''}">
                </div>`;
        default:
            return '<p style="color:#999;">请选择凭证类型</p>';
    }
}

function collectCredentialConfig(form, credentialType) {
    const formData = new FormData(form);
    const config = {};
    const putIfPresent = (key) => {
        const val = formData.get(key);
        if (val != null && String(val).trim() !== '') {
            config[key] = String(val).trim();
        }
    };

    switch (credentialType) {
        case 'username_password':
        case 'ssh_password':
            putIfPresent('username');
            putIfPresent('password');
            break;
        case 'token':
            putIfPresent('token');
            break;
        case 'api_key':
            putIfPresent('apiKey');
            break;
        case 'oauth':
            putIfPresent('clientId');
            putIfPresent('clientSecret');
            break;
        case 'kubeconfig':
            putIfPresent('configFile');
            putIfPresent('configContent');
            break;
        case 'ssh_key':
            putIfPresent('username');
            putIfPresent('privateKey');
            putIfPresent('publicKey');
            putIfPresent('passphrase');
            break;
    }
    return config;
}

function onCredentialTypeChange() {
    const typeSelect = document.getElementById('credentialTypeSelect');
    const fields = document.getElementById('credentialConfigFields');
    if (!typeSelect || !fields) return;
    fields.innerHTML = buildCredentialConfigFields(typeSelect.value, {});
}
window.onCredentialTypeChange = onCredentialTypeChange;

async function loadCredentials() {
    const list = document.getElementById('credentialList');
    if (!list) return;
    try {
        const response = await fetch('/api/credential/list');
        if (response.ok) {
            const credentials = await response.json();
            renderCredentialList(credentials || []);
            return;
        }
    } catch (error) {
        console.error('Load credentials error:', error);
    }
    renderCredentialList([]);
}
window.loadCredentials = loadCredentials;

function renderCredentialList(credentials) {
    const list = document.getElementById('credentialList');
    if (!list) return;
    if (!credentials.length) {
        list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:#999;">暂无钥匙</td></tr>';
        return;
    }
    list.innerHTML = credentials.map(item => `
        <tr>
            <td>${escKeychainHtml(item.name)}</td>
            <td>${escKeychainHtml(getCredentialTypeLabel(item.credentialType))}</td>
            <td>${escKeychainHtml(item.description || '-')}</td>
            <td>${item.status === 1 ? '<span style="color:#059669;">启用</span>' : '<span style="color:#dc2626;">禁用</span>'}</td>
            <td>
                <button class="btn-edit" onclick="editCredential(${item.id})">修改</button>
                <button class="btn-danger" onclick="deleteCredential(${item.id})">删除</button>
            </td>
        </tr>
    `).join('');
}

function showCreateCredentialModal() {
    const content = `
        <form id="createCredentialForm">
            <div class="form-item">
                <label>钥匙名称 *</label>
                <input type="text" name="name" required placeholder="例如：GitLab 主账号">
            </div>
            <div class="form-item">
                <label>凭证类型 *</label>
                <select name="credentialType" id="credentialTypeSelect" onchange="onCredentialTypeChange()" required>
                    <option value="">请选择类型</option>
                    ${renderCredentialTypeOptions()}
                </select>
            </div>
            <div id="credentialConfigFields"></div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="2"></textarea>
            </div>
        </form>
    `;
    showModal('添加钥匙', content, async () => {
        const form = document.getElementById('createCredentialForm');
        const formData = new FormData(form);
        const credentialType = formData.get('credentialType');
        const data = {
            name: (formData.get('name') || '').trim(),
            credentialType,
            configData: collectCredentialConfig(form, credentialType),
            description: formData.get('description') || ''
        };
        if (!data.name) {
            alert('钥匙名称不能为空');
            return;
        }
        try {
            const response = await fetch('/api/credential/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (response.ok) {
                alert('创建成功');
                loadCredentials();
                closeModal();
            } else {
                const err = await response.text();
                alert('创建失败: ' + err);
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}
window.showCreateCredentialModal = showCreateCredentialModal;

async function editCredential(id) {
    try {
        const response = await fetch(`/api/credential/${id}`);
        if (!response.ok) throw new Error('加载失败');
        const credential = await response.json();
        const content = `
            <form id="editCredentialForm">
                <div class="form-item">
                    <label>钥匙名称 *</label>
                    <input type="text" name="name" value="${escKeychainHtml(credential.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>凭证类型</label>
                    <input type="text" value="${escKeychainHtml(getCredentialTypeLabel(credential.credentialType))}" disabled>
                </div>
                <div id="credentialConfigFields">
                    ${buildCredentialConfigFields(credential.credentialType, credential.configData || {})}
                </div>
                <div class="form-item">
                    <label>描述</label>
                    <textarea name="description" rows="2">${escKeychainHtml(credential.description || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>状态</label>
                    <select name="status">
                        <option value="1" ${credential.status === 1 ? 'selected' : ''}>启用</option>
                        <option value="0" ${credential.status === 0 ? 'selected' : ''}>禁用</option>
                    </select>
                </div>
            </form>
        `;
        showModal('编辑钥匙', content, async () => {
            const form = document.getElementById('editCredentialForm');
            const formData = new FormData(form);
            const data = {
                name: (formData.get('name') || '').trim(),
                credentialType: credential.credentialType,
                configData: collectCredentialConfig(form, credential.credentialType),
                description: formData.get('description') || '',
                status: parseInt(formData.get('status'), 10)
            };
            const updateResp = await fetch(`/api/credential/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (updateResp.ok) {
                alert('修改成功');
                loadCredentials();
                closeModal();
            } else {
                alert('修改失败: ' + await updateResp.text());
            }
        });
    } catch (error) {
        alert('加载失败: ' + error.message);
    }
}
window.editCredential = editCredential;

async function deleteCredential(id) {
    if (!confirm('确定要删除这把钥匙吗？')) return;
    try {
        const response = await fetch(`/api/credential/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadCredentials();
        } else {
            alert('删除失败');
        }
    } catch (error) {
        alert('删除失败');
    }
}
window.deleteCredential = deleteCredential;

let cachedCredentials = [];

async function fetchCredentialsList() {
    try {
        const response = await fetch('/api/credential/list');
        if (response.ok) {
            cachedCredentials = await response.json();
            return cachedCredentials;
        }
    } catch (e) {
        console.error('fetchCredentialsList error', e);
    }
    return [];
}

function filterCredentialsForComponent(componentType) {
    const allowed = COMPONENT_CREDENTIAL_TYPES[componentType] || COMPONENT_CREDENTIAL_TYPES.other;
    return cachedCredentials.filter(c => c.status === 1 && allowed.includes(c.credentialType));
}

function renderComponentCredentialOptions(componentType, selectedId) {
    const list = filterCredentialsForComponent(componentType);
    const options = ['<option value="">手动填写认证信息</option>'];
    list.forEach(c => {
        const sel = String(c.id) === String(selectedId) ? ' selected' : '';
        options.push(`<option value="${c.id}"${sel}>${escKeychainHtml(c.name)} (${getCredentialTypeLabel(c.credentialType)})</option>`);
    });
    return options.join('');
}

async function initComponentCredentialSelect(componentType, selectedId) {
    await fetchCredentialsList();
    const select = document.getElementById('componentCredentialSelect');
    if (!select) return;
    select.innerHTML = renderComponentCredentialOptions(componentType || '', selectedId || '');
    onComponentCredentialChange();
}

function onComponentCredentialChange() {
    const select = document.getElementById('componentCredentialSelect');
    const manualSection = document.getElementById('componentManualAuthSection');
    if (!select || !manualSection) return;
    const useKeychain = !!select.value;
    manualSection.style.display = useKeychain ? 'none' : 'block';
    if (useKeychain) {
        const authTypeSelect = document.getElementById('authTypeSelect');
        if (authTypeSelect) authTypeSelect.removeAttribute('required');
    } else {
        const authTypeSelect = document.getElementById('authTypeSelect');
        if (authTypeSelect) authTypeSelect.setAttribute('required', 'required');
    }
}
window.onComponentCredentialChange = onComponentCredentialChange;

function buildComponentCredentialField(component) {
    component = component || {};
    const selectedId = component.credentialId || '';
    const useKeychain = !!selectedId;
    return `
        <div class="form-item">
            <label>关联钥匙串</label>
            <select name="credentialId" id="componentCredentialSelect" onchange="onComponentCredentialChange()">
                <option value="">加载中...</option>
            </select>
            <small style="color:#6b7280;">推荐从钥匙串选择凭证，避免在组件中重复填写</small>
        </div>
        <div id="componentManualAuthSection" style="display:${useKeychain ? 'none' : 'block'};">
            <div class="form-item">
                <label>认证类型 *</label>
                <select name="authType" id="authTypeSelect" onchange="window.updateAuthConfigFields()" ${useKeychain ? '' : 'required'}>
                    <option value="">请选择认证类型</option>
                    <option value="api_key" ${component.authType === 'api_key' ? 'selected' : ''}>API密钥</option>
                    <option value="username_password" ${component.authType === 'username_password' ? 'selected' : ''}>账户密码</option>
                    <option value="token" ${component.authType === 'token' ? 'selected' : ''}>Token</option>
                    <option value="oauth" ${component.authType === 'oauth' ? 'selected' : ''}>OAuth</option>
                </select>
            </div>
            <div id="authConfigFields"></div>
        </div>
    `;
}

function collectComponentAuthData(form) {
    const formData = new FormData(form);
    const credentialId = formData.get('credentialId');
    if (credentialId) {
        return { credentialId: parseInt(credentialId, 10) };
    }
    const authType = formData.get('authType');
    const authConfig = {};
    if (authType === 'api_key') {
        authConfig.apiKey = formData.get('apiKey') || '';
    } else if (authType === 'username_password') {
        authConfig.username = formData.get('username') || '';
        authConfig.password = formData.get('password') || '';
    } else if (authType === 'token') {
        authConfig.token = formData.get('token') || '';
    } else if (authType === 'oauth') {
        authConfig.clientId = formData.get('clientId') || '';
        authConfig.clientSecret = formData.get('clientSecret') || '';
    }
    return { authType, authConfig };
}

window.buildComponentCredentialField = buildComponentCredentialField;
window.collectComponentAuthData = collectComponentAuthData;
window.initComponentCredentialSelect = initComponentCredentialSelect;

function bindKeychainActions() {
    const btn = document.getElementById('addCredentialBtn');
    if (btn && btn.dataset.bound !== 'true') {
        btn.addEventListener('click', showCreateCredentialModal);
        btn.dataset.bound = 'true';
    }
}
window.bindKeychainActions = bindKeychainActions;

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindKeychainActions);
} else {
    bindKeychainActions();
}
