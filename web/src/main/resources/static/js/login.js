// 登录功能
document.getElementById('loginForm').addEventListener('submit', async function(e) {
    e.preventDefault();
    
    const username = document.getElementById('username').value;
    const password = document.getElementById('password').value;
    const errorMsg = document.getElementById('errorMsg');
    
    errorMsg.textContent = '';
    
    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ username, password })
        });
        
        const data = await response.json();
        
        if (data.success) {
            // 登录成功，跳转到控制台
            if (data.token) {
                localStorage.setItem('token', data.token);
            }
            window.location.href = '/dashboard.html';
        } else {
            errorMsg.textContent = data.message || '登录失败，请检查用户名和密码';
        }
    } catch (error) {
        errorMsg.textContent = '登录失败，请稍后重试';
        console.error('Login error:', error);
    }
});

