// ============================================
// 钥匙串管理模块
// ============================================

const CREDENTIAL_TYPE_OPTIONS = [
    // label 用 getter：随当前语言变化，不在加载时固定
    { value: 'username_password', get label() { return t('keychain.type.usernamePassword'); } },
    { value: 'token', label: 'Token' },
    { value: 'api_key', get label() { return t('keychain.type.apiKey'); } },
    { value: 'oauth', label: 'OAuth' },
    { value: 'kubeconfig', get label() { return t('keychain.type.kubeconfig'); } },
    { value: 'ssh_password', get label() { return t('keychain.type.sshPassword'); } },
    { value: 'ssh_key', get label() { return t('keychain.type.sshKey'); } }
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

function getCredentialTypeLabel(type) {
    const item = CREDENTIAL_TYPE_OPTIONS.find(o => o.value === type);
    return item ? item.label : (type || '-');
}

function renderCredentialTypeOptions(selected) {
    return CREDENTIAL_TYPE_OPTIONS.map(opt => {
        const sel = selected === opt.value ? ' selected' : '';
        return `<option value="${escapeHtml(opt.value)}"${sel}>${escapeHtml(opt.label)}</option>`;
    }).join('');
}

function buildCredentialConfigFields(credentialType, configData) {
    configData = configData || {};
    switch (credentialType) {
        case 'username_password':
        case 'ssh_password':
            return `
                <div class="form-item">
                    <label>${t('keychain.form.username')}${REQUIRED_MARK}</label>
                    <input type="text" name="username" value="${escapeHtml(configData.username || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('keychain.form.password')}${REQUIRED_MARK}</label>
                    <input type="password" name="password" placeholder="${configData.password === '******' ? t('keychain.form.keep') : t('keychain.form.passwordPlaceholder')}" ${configData.password === '******' ? '' : 'required'}>
                    <small style="color:#6b7280;">${t('keychain.form.passwordHint')}</small>
                </div>`;
        case 'token':
            return `
                <div class="form-item">
                    <label>Token${REQUIRED_MARK}</label>
                    <input type="password" name="token" placeholder="${configData.token === '******' ? t('keychain.form.keep') : t('keychain.form.tokenPlaceholder')}" ${configData.token === '******' ? '' : 'required'}>
                    <small style="color:#6b7280;">${t('keychain.form.tokenHint')}</small>
                </div>`;
        case 'api_key':
            return `
                <div class="form-item">
                    <label>${t('keychain.form.apiKey')}${REQUIRED_MARK}</label>
                    <input type="password" name="apiKey" placeholder="${configData.apiKey === '******' ? t('keychain.form.keep') : ''}" ${configData.apiKey === '******' ? '' : 'required'}>
                </div>`;
        case 'oauth':
            return `
                <div class="form-item">
                    <label>Client ID${REQUIRED_MARK}</label>
                    <input type="text" name="clientId" value="${escapeHtml(configData.clientId || '')}" required>
                </div>
                <div class="form-item">
                    <label>Client Secret${REQUIRED_MARK}</label>
                    <input type="password" name="clientSecret" placeholder="${configData.clientSecret === '******' ? t('keychain.form.keep') : ''}" ${configData.clientSecret === '******' ? '' : 'required'}>
                </div>`;
        case 'kubeconfig':
            return `
                <div class="form-item">
                    <label>${t('keychain.form.kubeconfigPath')}</label>
                    <input type="text" name="configFile" value="${escapeHtml(configData.configFile || '')}" placeholder="/path/to/kubeconfig">
                </div>
                <div class="form-item">
                    <label>${t('keychain.form.kubeconfigContent')}</label>
                    <textarea name="configContent" rows="8" placeholder="${t('keychain.form.kubeconfigPlaceholder')}">${configData.configContent === '******' ? '' : escapeHtml(configData.configContent || '')}</textarea>
                    <small style="color:#6b7280;">${t('keychain.form.kubeconfigHint')}</small>
                </div>`;
        case 'ssh_key':
            return `
                <div class="form-item">
                    <label>${t('keychain.form.sshUsername')}${REQUIRED_MARK}</label>
                    <input type="text" name="username" value="${escapeHtml(configData.username || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('keychain.form.privateKey')}${REQUIRED_MARK}</label>
                    <textarea name="privateKey" rows="6" placeholder="${configData.privateKey === '******' ? t('keychain.form.keep') : '-----BEGIN OPENSSH PRIVATE KEY-----'}" ${configData.privateKey === '******' ? '' : 'required'}>${configData.privateKey === '******' ? '' : escapeHtml(configData.privateKey || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>${t('keychain.form.publicKey')}</label>
                    <textarea name="publicKey" rows="3" placeholder="ssh-rsa AAAA...">${escapeHtml(configData.publicKey || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>${t('keychain.form.passphrase')}</label>
                    <input type="password" name="passphrase" placeholder="${configData.passphrase === '******' ? t('keychain.form.keep') : ''}">
                </div>`;
        default:
            return ('<p style="color:var(--text-4);">' + t('keychain.form.selectTypeFirst') + '</p>');
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
        list.innerHTML = ('<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">' + t('keychain.empty') + '</td></tr>');
        return;
    }
    list.innerHTML = credentials.map(item => `
        <tr>
            <td>${escapeHtml(item.name)}</td>
            <td>${escapeHtml(getCredentialTypeLabel(item.credentialType))}</td>
            <td>${escapeHtml(item.description || '-')}</td>
            <td>${item.status === 1 ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>') : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>')}</td>
            <td>
                <div class="table-actions">
                    <button class="btn-edit" onclick="editCredential(${item.id})">${t('common.modify')}</button>
                    <button class="btn-danger" onclick="deleteCredential(${item.id})">${t('common.delete')}</button>
                </div>
            </td>
        </tr>
    `).join('');
}

function showCreateCredentialModal() {
    const content = `
        <form id="createCredentialForm">
            <div class="form-item">
                <label>${t('keychain.form.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" required placeholder="${t('keychain.form.namePlaceholder')}">
            </div>
            <div class="form-item">
                <label>${t('keychain.form.type')}${REQUIRED_MARK}</label>
                <select name="credentialType" id="credentialTypeSelect" onchange="onCredentialTypeChange()" required>
                    <option value="">${t('keychain.form.selectType')}</option>
                    ${renderCredentialTypeOptions()}
                </select>
            </div>
            <div id="credentialConfigFields"></div>
            <div class="form-item">
                <label>${t('common.description')}</label>
                <textarea name="description" rows="2"></textarea>
            </div>
        </form>
    `;
    showModal(t('keychain.form.createTitle'), content, async () => {
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
            notify(t('keychain.msg.nameRequired'));
            return;
        }
        try {
            const response = await fetch('/api/credential/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (response.ok) {
                notify(t('common.createOk'));
                loadCredentials();
                closeModal();
            } else {
                const err = await response.text();
                notify((t('common.createFailed') + ': ') + err);
            }
        } catch (error) {
            notify((t('common.createFailed') + ': ') + error.message);
        }
    });
}
window.showCreateCredentialModal = showCreateCredentialModal;

async function editCredential(id) {
    try {
        const response = await fetch(`/api/credential/${id}`);
        if (!response.ok) throw new Error(t('common.loadFailed'));
        const credential = await response.json();
        const content = `
            <form id="editCredentialForm">
                <div class="form-item">
                    <label>${t('keychain.form.name')}${REQUIRED_MARK}</label>
                    <input type="text" name="name" value="${escapeHtml(credential.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('keychain.form.type')}</label>
                    <input type="text" value="${escapeHtml(getCredentialTypeLabel(credential.credentialType))}" disabled>
                </div>
                <div id="credentialConfigFields">
                    ${buildCredentialConfigFields(credential.credentialType, credential.configData || {})}
                </div>
                <div class="form-item">
                    <label>${t('common.description')}</label>
                    <textarea name="description" rows="2">${escapeHtml(credential.description || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>${t('common.status')}</label>
                    <select name="status">
                        <option value="1" ${credential.status === 1 ? 'selected' : ''}>${t('common.enabled')}</option>
                        <option value="0" ${credential.status === 0 ? 'selected' : ''}>${t('common.disabled')}</option>
                    </select>
                </div>
            </form>
        `;
        showModal(t('keychain.form.editTitle'), content, async () => {
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
                notify(t('common.updateOk'));
                loadCredentials();
                closeModal();
            } else {
                notify((t('common.updateFailed') + ': ') + await updateResp.text());
            }
        });
    } catch (error) {
        notify((t('common.loadFailed') + ': ') + error.message);
    }
}
window.editCredential = editCredential;

async function deleteCredential(id) {
    if (!await confirmDialog(t('keychain.msg.deleteConfirm'))) return;
    try {
        const response = await fetch(`/api/credential/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            loadCredentials();
        } else {
            notify(t('common.deleteFailed'));
        }
    } catch (error) {
        notify(t('common.deleteFailed'));
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
    const options = [('<option value="">' + t('keychain.component.manual') + '</option>')];
    list.forEach(c => {
        const sel = String(c.id) === String(selectedId) ? ' selected' : '';
        options.push(`<option value="${c.id}"${sel}>${escapeHtml(c.name)} (${escapeHtml(getCredentialTypeLabel(c.credentialType))})</option>`);
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
            <label>${t('keychain.component.link')}</label>
            <select name="credentialId" id="componentCredentialSelect" onchange="onComponentCredentialChange()">
                <option value="">${t('common.loading')}</option>
            </select>
            <small style="color:#6b7280;">${t('keychain.component.linkHint')}</small>
        </div>
        <div id="componentManualAuthSection" style="display:${useKeychain ? 'none' : 'block'};">
            <div class="form-item">
                <label>${t('keychain.component.authType')}${REQUIRED_MARK}</label>
                <select name="authType" id="authTypeSelect" onchange="window.updateAuthConfigFields()" ${useKeychain ? '' : 'required'}>
                    <option value="">${t('keychain.component.selectAuthType')}</option>
                    <option value="api_key" ${component.authType === 'api_key' ? 'selected' : ''}>${t('keychain.type.apiKey')}</option>
                    <option value="username_password" ${component.authType === 'username_password' ? 'selected' : ''}>${t('keychain.type.usernamePassword')}</option>
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
