// ==================== 会话存储 ====================
// Token 放 sessionStorage 而不是 localStorage：关掉标签页就没了。
// 这台机器是压测机，同一个浏览器里可能来回切管理员/普通用户身份，
// 用 sessionStorage 能保证每个标签页一套身份，不会互相串号。
// 代价：新开标签页要重新登录，这是刻意的。
const API_BASE = '/api';

/** 当前标签页的 JWT；没有就是未登录 */
function getToken() {
    return sessionStorage.getItem('token');
}

/** 登录名，仅用于界面显示与审计日志展示，鉴权不依赖它（服务端只认 Token） */
function getUsername() {
    return sessionStorage.getItem('username');
}

/** 角色：0 普通用户 / 1 管理员。取自登录响应，只用来决定菜单显隐 */
function getRole() {
    return parseInt(sessionStorage.getItem('role') || '0');
}

/** 前端判定是否为管理员。注意这只是界面层，真正的 403 由服务端 AdminInterceptor 决定 */
function isAdmin() {
    return getRole() === 1;
}

/** 登录后一次性写入三项；role 为空按 0 处理，避免出现 'null' 字符串 */
function saveSession(token, username, role) {
    sessionStorage.setItem('token', token);
    sessionStorage.setItem('username', username);
    sessionStorage.setItem('role', role != null ? role : 0);
}

/** 清空本标签页会话。服务端 Token 仍是有效的（无状态 JWT），所以“退出”不等于失效 */
function clearSession() {
    sessionStorage.clear();
}

/** 只看有没有 Token，不校验有效性；过期要等接口回 401 才会被 apiFetch 统一处理 */
function isLoggedIn() {
    return !!getToken();
}

/** 清会话并回登录页。接口返回 401 时也是走这里 */
function redirectToLogin() {
    clearSession();
    window.location.href = '/login.html';
}

function redirectToIndex() {
    window.location.href = '/index.html';
}

/**
 * 统一请求出口：
 * 1. 网络异常 / 非 JSON 响应（网关错误页、Spring 默认错误体）不再抛异常，而是归一化成 {code,msg,data}
 * 2. code 缺失时用 HTTP 状态码补齐，前端能显示真实原因而不是“操作失败”
 * 3. 401 自动跳登录（登录接口自身除外）
 */
async function apiRequest(path, options, skipAuthRedirect) {
    var res, text, data;
    try {
        res = await fetch(API_BASE + path, options);
        text = await res.text();
    } catch (e) {
        return { code: -1, msg: '无法连接服务器（' + (e && e.message ? e.message : 'network error') + '）', data: null };
    }
    try {
        data = text ? JSON.parse(text) : {};
    } catch (e) {
        data = {};
    }
    // 响应可能是字面量 null / 数组 / 字符串，归一化成对象后再读 code，避免 TypeError 抛给调用方
    if (data === null || typeof data !== 'object' || Array.isArray(data)) data = {};
    if (typeof data.code !== 'number') {
        var st = (typeof data.status === 'number' && data.status) ? data.status : res.status;
        data.code = (st === 200) ? 500 : st;
        data.msg = data.msg || data.error || (st === 403 ? '无管理员权限' : '请求失败 (HTTP ' + st + ')');
    }
    if (data.code === 401 && !skipAuthRedirect) {
        redirectToLogin();
    }
    return data;
}

async function apiGet(path) {
    return apiRequest(path, {
        headers: { 'token': getToken() || '' }
    });
}

async function apiPost(path, body, extraHeaders, skipAuthRedirect) {
    var headers = { 'Content-Type': 'application/json' };
    if (extraHeaders) {
        Object.assign(headers, extraHeaders);
    }
    if (getToken()) {
        headers['token'] = getToken();
    }
    return apiRequest(path, {
        method: 'POST',
        headers: headers,
        body: body == null ? '{}' : JSON.stringify(body)
    }, skipAuthRedirect);
}

async function apiPut(path, body) {
    var headers = { 'Content-Type': 'application/json' };
    if (getToken()) {
        headers['token'] = getToken();
    }
    return apiRequest(path, {
        method: 'PUT',
        headers: headers,
        body: body == null ? '{}' : JSON.stringify(body)
    });
}

async function apiDelete(path) {
    var headers = {};
    if (getToken()) {
        headers['token'] = getToken();
    }
    return apiRequest(path, {
        method: 'DELETE',
        headers: headers
    });
}

/** 右上角轻提示。type: success / error / warning / info */
function toast(msg, type) {
    type = type || 'success';
    var el = document.createElement('div');
    el.className = 'toast ' + type;
    el.textContent = msg == null || msg === '' ? '操作失败' : String(msg);
    document.body.appendChild(el);
    setTimeout(function() { el.remove(); }, 2500);
}

/**
 * 后端错误文案是英文（便于接口测试），管理页面须展示中文。
 * 未命中映射时原样透出 msg，不藏信息。
 */
var MSG_ZH = {
    'token is missing': '未登录或登录状态已丢失，请重新登录',
    'token has expired': '登录已过期，请重新登录',
    'invalid token': '登录凭证无效，请重新登录',
    'admin permission required': '无管理员权限',
    'admin permission required, your role has been changed, please login again': '管理员角色已被修改，请重新登录',
    'account has been deleted, please login again': '账号已被删除，请重新登录',
    'invalid username or password': '用户名或密码错误',
    'username, password and ts header are required': '用户名、密码和 ts 请求头不能为空',
    'invalid timestamp format': '时间戳格式错误',
    'timestamp expired, possible replay attack': '时间戳已过期（超过 5 分钟），请重试',
    'username is required': '用户名不能为空',
    'password is required': '密码不能为空',
    'username too long (max 50)': '用户名长度不能超过 50',
    'password too long (max 100)': '密码长度不能超过 100',
    'role must be 0 or 1': '角色只能是 0（普通用户）或 1（管理员）',
    'username already exists': '用户名已存在',
    'goods name already exists': '商品名已存在（商品名不允许重复）',
    'invalid request body': '请求体不是合法 JSON',
    'nothing to update: password or role is required': '请至少填写密码或角色',
    'nothing to update: goodsName, price or stock is required': '请至少填写商品名称、单价或库存',
    'cannot remove admin role from your own account': '不能取消当前登录账号的管理员角色',
    'at least one admin account is required': '系统至少需要保留一个管理员账号',
    'cannot delete your own account': '不能删除当前登录的账号',
    'user not found': '用户不存在',
    'user still has orders, delete orders first': '该用户还有订单，不能删除',
    'goodsName is required': '商品名称不能为空',
    'goodsName too long (max 200)': '商品名称长度不能超过 200',
    'price is required': '请填写单价',
    'stock is required': '请填写库存',
    'price must be greater than or equal to 0': '单价不能为负数',
    'price too large (max 99999999.99)': '单价超出上限 99999999.99',
    'stock must be greater than or equal to 0': '库存不能为负数',
    'goods not found': '商品不存在',
    'goods still has orders, delete orders first': '该商品存在订单，不能删除',
    'insufficient stock': '库存不足',
    'order not found': '订单不存在',
    'invalid orderNo': '订单号格式不正确',
    'forbidden: not your order': '这不是你的订单，无法支付',
    'order already paid': '该订单已是已支付状态',
    'account not found, please login again': '账号不存在或已被删除，请重新登录',
    'userId or goodsId is required': '请指定要清理订单的用户 ID 或商品 ID',
    'record already exists': '记录已存在（唯一约束冲突）',
    'invalid field value for this operation': '字段值不符合约束',
    'user not found or deleted': '账号不存在或已被删除'
};

/** 把后端返回体翻译成可读提示 */
function sleep(ms) {
    return new Promise(function (r) { setTimeout(r, ms); });
}

function errMsg(data, fallback) {
    if (!data) return fallback || '操作失败';
    var msg = data.msg;
    if (!msg) return (data.code === 403) ? '没有权限执行该操作' : (fallback || '操作失败');
    // 先查具体文案映射，再用“权限”兼底：否则越权支付（403 not your order）会被误报成“无管理员权限”
    if (MSG_ZH[msg]) return MSG_ZH[msg];
    if (data.code === 403) return '没有权限执行该操作：' + msg;
    var IP = 'invalid parameter:';
    if (msg.indexOf(IP) === 0) return '参数格式不正确：' + msg.slice(IP.length).trim();
    var H = 'missing required header:';
    var P = 'missing required parameter:';
    var I = 'internal server error:';
    if (msg.indexOf(H) === 0) return '缺少请求头：' + msg.slice(H.length).trim();
    if (msg.indexOf(P) === 0) return '缺少参数：' + msg.slice(P.length).trim();
    if (msg.indexOf(I) === 0) return '服务器内部错误：' + msg.slice(I.length).trim();
    if (msg.indexOf('Content-Type') === 0) return '请求的 Content-Type 必须是 application/json';
    return msg;
}

// ==================== 通用展示/安全辅助（admin.html 与 index.html 共用）====================
/** 只用于 HTML 元素内容；不转义引号，所以不要用来拼属性值 */
function esc(s) {
    var d = document.createElement('div');
    d.textContent = (s == null ? '' : String(s));
    return d.innerHTML;
}
/**
 * 属性值转义。esc() 只管文本节点（不转引号），拼进 data-* / title 必须用这个，
 * 否则值里有引号就能逃逸出属性。
 */
function attr(v) {
    return String(v == null ? '' : v)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** 行内按钮的 data-id：只接受正整数，别的宁可返回 null 也不拼进 HTML（不靠转义兜底） */
function attrId(v) {
    var n = Number(v);
    return (Number.isFinite(n) && n > 0) ? n : null;
}
/** 金额保留两位小数；后端给的是 BigDecimal 序列化出的数字，null/NaN 一律显示 '-' */
function fmtAmount(v) {
    var n = Number(v);
    return (v == null || isNaN(n)) ? '-' : n.toFixed(2);
}
/** 带人民币符号的金额 */
function fmtMoney(v) {
    var s = fmtAmount(v);
    return s === '-' ? s : '¥' + s;
}
/**
 * 时间显示。兼容两种后端形态：毫秒时间戳（orders.createTs）和
 * "2026-07-23T10:00:00" 字符串（LocalDateTime 序列化，截掉秒后面的部分）。
 */
function fmtTime(v) {
    if (v == null || v === '') return '-';
    if (typeof v === 'number') return new Date(v).toLocaleString('zh-CN');
    var s = String(v).replace('T', ' ');
    return s.length > 19 ? s.substring(0, 19) : s;
}

function md5(str) {
    // 纯 JS 实现的 MD5（公有领域参考实现，内部逐位运算函数不再逐个注释）。
    // 口令要先 utf8Encode 再取摘要，才能与服务端 Md5Util（固定按 UTF-8 取字节）算出同一个值。
    // 这一步不能省：中文口令在非 UTF-8 的运行时下两边摘要不同，表现为密码正确却登不进去。
    function rotateLeft(lValue, iShiftBits) {
        return (lValue << iShiftBits) | (lValue >>> (32 - iShiftBits));
    }
    function addUnsigned(lX, lY) {
        var lX4, lY4, lX8, lY8, lResult;
        lX8 = (lX & 0x80000000);
        lY8 = (lY & 0x80000000);
        lX4 = (lX & 0x40000000);
        lY4 = (lY & 0x40000000);
        lResult = (lX & 0x3FFFFFFF) + (lY & 0x3FFFFFFF);
        if (lX4 & lY4) return (lResult ^ 0x80000000 ^ lX8 ^ lY8);
        if (lX4 | lY4) {
            if (lResult & 0x40000000) return (lResult ^ 0xC0000000 ^ lX8 ^ lY8);
            else return (lResult ^ 0x40000000 ^ lX8 ^ lY8);
        } else {
            return (lResult ^ lX8 ^ lY8);
        }
    }
    function F(x, y, z) { return (x & y) | ((~x) & z); }
    function G(x, y, z) { return (x & z) | (y & (~z)); }
    function H(x, y, z) { return (x ^ y ^ z); }
    function I(x, y, z) { return (y ^ (x | (~z))); }
    function FF(a, b, c, d, x, s, ac) { a = addUnsigned(a, addUnsigned(addUnsigned(F(b, c, d), x), ac)); return addUnsigned(rotateLeft(a, s), b); }
    function GG(a, b, c, d, x, s, ac) { a = addUnsigned(a, addUnsigned(addUnsigned(G(b, c, d), x), ac)); return addUnsigned(rotateLeft(a, s), b); }
    function HH(a, b, c, d, x, s, ac) { a = addUnsigned(a, addUnsigned(addUnsigned(H(b, c, d), x), ac)); return addUnsigned(rotateLeft(a, s), b); }
    function II(a, b, c, d, x, s, ac) { a = addUnsigned(a, addUnsigned(addUnsigned(I(b, c, d), x), ac)); return addUnsigned(rotateLeft(a, s), b); }
    function convertToWordArray(str) {
        var lWordCount, lMessageLength = str.length, lNumberOfWords_temp1 = lMessageLength + 8, lNumberOfWords_temp2 = (lNumberOfWords_temp1 - (lNumberOfWords_temp1 % 64)) / 64, lNumberOfWords = (lNumberOfWords_temp2 + 1) * 16, lWordArray = Array(lNumberOfWords - 1), lBytePosition = 0, lByteCount = 0;
        while (lByteCount < lMessageLength) {
            lWordCount = (lByteCount - (lByteCount % 4)) / 4;
            lBytePosition = (lByteCount % 4) * 8;
            lWordArray[lWordCount] = (lWordArray[lWordCount] | (str.charCodeAt(lByteCount) << lBytePosition));
            lByteCount++;
        }
        lWordCount = (lByteCount - (lByteCount % 4)) / 4;
        lBytePosition = (lByteCount % 4) * 8;
        lWordArray[lWordCount] = lWordArray[lWordCount] | (0x80 << lBytePosition);
        lWordArray[lNumberOfWords - 2] = lMessageLength << 3;
        lWordArray[lNumberOfWords - 1] = lMessageLength >>> 29;
        return lWordArray;
    }
    function wordToHex(lValue) {
        var wordToHexValue = "", wordToHexValue_temp = "", lByte, lCount;
        for (lCount = 0; lCount <= 3; lCount++) {
            lByte = (lValue >>> (lCount * 8)) & 255;
            wordToHexValue_temp = "0" + lByte.toString(16);
            wordToHexValue = wordToHexValue + wordToHexValue_temp.substr(wordToHexValue_temp.length - 2, 2);
        }
        return wordToHexValue;
    }
    var x = Array(), k, AA, BB, CC, DD, a, b, c, d, S11 = 7, S12 = 12, S13 = 17, S14 = 22, S21 = 5, S22 = 9, S23 = 14, S24 = 20, S31 = 4, S32 = 11, S33 = 16, S34 = 23, S41 = 6, S42 = 10, S43 = 15, S44 = 21;
    str = utf8Encode(str);
    x = convertToWordArray(str);
    a = 0x67452301; b = 0xEFCDAB89; c = 0x98BADCFE; d = 0x10325476;
    for (k = 0; k < x.length; k += 16) {
        AA = a; BB = b; CC = c; DD = d;
        a = FF(a, b, c, d, x[k], S11, 0xD76AA478);
        d = FF(d, a, b, c, x[k + 1], S12, 0xE8C7B756);
        c = FF(c, d, a, b, x[k + 2], S13, 0x242070DB);
        b = FF(b, c, d, a, x[k + 3], S14, 0xC1BDCEEE);
        a = FF(a, b, c, d, x[k + 4], S11, 0xF57C0FAF);
        d = FF(d, a, b, c, x[k + 5], S12, 0x4787C62A);
        c = FF(c, d, a, b, x[k + 6], S13, 0xA8304613);
        b = FF(b, c, d, a, x[k + 7], S14, 0xFD469501);
        a = FF(a, b, c, d, x[k + 8], S11, 0x698098D8);
        d = FF(d, a, b, c, x[k + 9], S12, 0x8B44F7AF);
        c = FF(c, d, a, b, x[k + 10], S13, 0xFFFF5BB1);
        b = FF(b, c, d, a, x[k + 11], S14, 0x895CD7BE);
        a = FF(a, b, c, d, x[k + 12], S11, 0x6B901122);
        d = FF(d, a, b, c, x[k + 13], S12, 0xFD987193);
        c = FF(c, d, a, b, x[k + 14], S13, 0xA679438E);
        b = FF(b, c, d, a, x[k + 15], S14, 0x49B40821);
        a = GG(a, b, c, d, x[k + 1], S21, 0xF61E2562);
        d = GG(d, a, b, c, x[k + 6], S22, 0xC040B340);
        c = GG(c, d, a, b, x[k + 11], S23, 0x265E5A51);
        b = GG(b, c, d, a, x[k], S24, 0xE9B6C7AA);
        a = GG(a, b, c, d, x[k + 5], S21, 0xD62F105D);
        d = GG(d, a, b, c, x[k + 10], S22, 0x2441453);
        c = GG(c, d, a, b, x[k + 15], S23, 0xD8A1E681);
        b = GG(b, c, d, a, x[k + 4], S24, 0xE7D3FBC8);
        a = GG(a, b, c, d, x[k + 9], S21, 0x21E1CDE6);
        d = GG(d, a, b, c, x[k + 14], S22, 0xC33707D6);
        c = GG(c, d, a, b, x[k + 3], S23, 0xF4D50D87);
        b = GG(b, c, d, a, x[k + 8], S24, 0x455A14ED);
        a = GG(a, b, c, d, x[k + 13], S21, 0xA9E3E905);
        d = GG(d, a, b, c, x[k + 2], S22, 0xFCEFA3F8);
        c = GG(c, d, a, b, x[k + 7], S23, 0x676F02D9);
        b = GG(b, c, d, a, x[k + 12], S24, 0x8D2A4C8A);
        a = HH(a, b, c, d, x[k + 5], S31, 0xFFFA3942);
        d = HH(d, a, b, c, x[k + 8], S32, 0x8771F681);
        c = HH(c, d, a, b, x[k + 11], S33, 0x6D9D6122);
        b = HH(b, c, d, a, x[k + 14], S34, 0xFDE5380C);
        a = HH(a, b, c, d, x[k + 1], S31, 0xA4BEEA44);
        d = HH(d, a, b, c, x[k + 4], S32, 0x4BDECFA9);
        c = HH(c, d, a, b, x[k + 7], S33, 0xF6BB4B60);
        b = HH(b, c, d, a, x[k + 10], S34, 0xBEBFBC70);
        a = HH(a, b, c, d, x[k + 13], S31, 0x289B7EC6);
        d = HH(d, a, b, c, x[k], S32, 0xEAA127FA);
        c = HH(c, d, a, b, x[k + 3], S33, 0xD4EF3085);
        b = HH(b, c, d, a, x[k + 6], S34, 0x4881D05);
        a = HH(a, b, c, d, x[k + 9], S31, 0xD9D4D039);
        d = HH(d, a, b, c, x[k + 12], S32, 0xE6DB99E5);
        c = HH(c, d, a, b, x[k + 15], S33, 0x1FA27CF8);
        b = HH(b, c, d, a, x[k + 2], S34, 0xC4AC5665);
        a = II(a, b, c, d, x[k], S41, 0xF4292244);
        d = II(d, a, b, c, x[k + 7], S42, 0x432AFF97);
        c = II(c, d, a, b, x[k + 14], S43, 0xAB9423A7);
        b = II(b, c, d, a, x[k + 5], S44, 0xFC93A039);
        a = II(a, b, c, d, x[k + 12], S41, 0x655B59C3);
        d = II(d, a, b, c, x[k + 3], S42, 0x8F0CCC92);
        c = II(c, d, a, b, x[k + 10], S43, 0xFFEFF47D);
        b = II(b, c, d, a, x[k + 1], S44, 0x85845DD1);
        a = II(a, b, c, d, x[k + 8], S41, 0x6FA87E4F);
        d = II(d, a, b, c, x[k + 15], S42, 0xFE2CE6E0);
        c = II(c, d, a, b, x[k + 6], S43, 0xA3014314);
        b = II(b, c, d, a, x[k + 13], S44, 0x4E0811A1);
        a = II(a, b, c, d, x[k + 4], S41, 0xF7537E82);
        d = II(d, a, b, c, x[k + 11], S42, 0xBD3AF235);
        c = II(c, d, a, b, x[k + 2], S43, 0x2AD7D2BB);
        b = II(b, c, d, a, x[k + 9], S44, 0xEB86D391);
        a = addUnsigned(a, AA); b = addUnsigned(b, BB); c = addUnsigned(c, CC); d = addUnsigned(d, DD);
    }
    return (wordToHex(a) + wordToHex(b) + wordToHex(c) + wordToHex(d)).toLowerCase();
}

function utf8Encode(str) {
    str = str.replace(/\r\n/g, '\n');
    var utftext = '';
    for (var n = 0; n < str.length; n++) {
        var c = str.charCodeAt(n);
        if (c < 128) { utftext += String.fromCharCode(c); }
        else if ((c > 127) && (c < 2048)) { utftext += String.fromCharCode((c >> 6) | 192); utftext += String.fromCharCode((c & 63) | 128); }
        else { utftext += String.fromCharCode((c >> 12) | 224); utftext += String.fromCharCode(((c >> 6) & 63) | 128); utftext += String.fromCharCode((c & 63) | 128); }
    }
    return utftext;
}

// ==================== 操作日志：动作与中文文案 ====================
// 操作类型英文枚举 -> 中文。后端只存枚举名（便于接口与日志检索），界面一律展示中文，
// 原始枚举放在单元格的 title 里，对照接口文档时能看到。
// 这里必须是唯一的口径来源：新增 action 时同步补接口文档，漏了会让日志出现裸枚举名。
var ACTION_ZH = {
    ADMIN_LOGIN: '管理员登录',
    ADMIN_LOGOUT: '管理员退出',
    USER_LOGIN: '用户登录',
    USER_LOGOUT: '用户退出',
    PLACE_ORDER: '用户下单',
    DB_RESET: '重置测试数据',
    PAY_ORDER: '用户支付',
    CREATE_USER: '新增用户',
    UPDATE_USER: '编辑用户',
    DELETE_USER: '删除用户',
    CREATE_GOODS: '新增商品',
    UPDATE_GOODS: '编辑商品',
    DELETE_GOODS: '删除商品',
    DELETE_ORDER: '删除订单'
};

// 所有已知动作（下拉筛选的候选，顺序即展示顺序）
var ACTION_ORDER = ['ADMIN_LOGIN', 'ADMIN_LOGOUT', 'USER_LOGIN', 'USER_LOGOUT', 'PLACE_ORDER', 'PAY_ORDER',
    'CREATE_USER', 'UPDATE_USER', 'DELETE_USER', 'CREATE_GOODS', 'UPDATE_GOODS', 'DELETE_GOODS', 'DELETE_ORDER', 'DB_RESET'];

/** 操作类型英文枚举 -> 中文；未识别的归到“其他”，绝不把裸枚举丢给用户看 */
function actionLabel(action) {
    return ACTION_ZH[action] || '其他操作';
}

/** 日志表格里的“操作类型”单元格：主文案中文，原始枚举放 title，便于对照接口与排查 */
/**
 * 日志表“操作类型”单元格：只显示中文（业务同学看的），
 * 原始枚举放 title，排查时鼠标悬停即可看到，不必再猜 PLACE_ORDER 是什么。
 */
function actionCell(action) {
    var code = attr(action || '');
    return '<span class="action-tag" title="' + code + '">' + esc(actionLabel(action)) + '</span>';
}

// ==================== 退出登录 ====================
/**
 * 先让服务端记一条退出日志，再清本地会话并跳转。
 * 服务端用的是无状态 JWT，Token 本身在过期前仍可用；这里的“退出”= 写审计 + 客户端清 Token。
 * 即使接口失败也必须退出，所以是 best-effort。
 */
var loggingOut = false;

async function logoutCurrent(target, btn) {
    if (loggingOut) return;                 // 连点两次会写出两条退出日志
    loggingOut = true;
    if (btn) btn.disabled = true;
    try {
        // 审计只是尽力而为，最多等 1.5s，不能让退出卡在请求上
        await Promise.race([apiPost('/user/logout', undefined, undefined, true), sleep(1500)]);
    } catch (e) {
        // 网络异常不影响退出
    }
    clearSession();
    window.location.href = target || '/login.html';
}

// ==================== Hash 路由（admin.html / index.html 共用）====================
// 页面面板状态写进 URL：刷新、收藏、前进后退都能还原当前视图。
var HashRoute = {
    /** 解析 #/name?a=1&b=2；未知 name 回落 home */
    parse: function (names, home) {
        var h = String(window.location.hash || '').replace(/^#\/?/, '');
        var i = h.indexOf('?');
        var name = (i < 0 ? h : h.substring(0, i)) || home;
        if (names.indexOf(name) < 0) name = home;
        var query = {};
        if (i >= 0) {
            h.substring(i + 1).split('&').forEach(function (kv) {
                if (!kv) return;
                var t = kv.split('=');
                try {
                    query[decodeURIComponent(t[0])] = decodeURIComponent(t[1] || '');
                } catch (e) {
                    /* 半截百分号编码：忽略该参数，其余照常解析 */
                }
            });
        }
        return { name: name, query: query };
    },

    /** params -> query 串；值等于 defaults 或为空时省略，保持地址干净 */
    build: function (name, params, defaults) {
        var parts = [];
        Object.keys(params).forEach(function (key) {
            var value = params[key];
            if (value === '' || value === null || value === undefined) return;
            if (defaults && String(value) === String(defaults[key])) return;
            parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
        });
        return '#/' + name + (parts.length ? '?' + parts.join('&') : '');
    },

    /**
     * 用 replaceState 写 hash：不新增历史记录，浏览器后退仍然回到“上一个面板”。
     * 不支持 replaceState 的环境退化为直接改 hash。
     */
    write: function (name, params, defaults) {
        var target = HashRoute.build(name, params, defaults);
        if (window.location.hash === target) return false;
        if (window.history && window.history.replaceState) {
            window.history.replaceState(null, '', window.location.pathname + window.location.search + target);
        } else {
            window.location.hash = target;
        }
        return true;
    },

    /** 首次进入没有 hash 时补默认路由（同样不产生历史记录） */
    ensureHome: function (home) {
        if (window.location.hash) return;
        var target = '#/' + home;
        if (window.history && window.history.replaceState) {
            window.history.replaceState(null, '', window.location.pathname + window.location.search + target);
        } else {
            window.location.hash = target;
        }
    },

    watch: function (handler) {
        window.addEventListener('hashchange', handler);
    }
};
